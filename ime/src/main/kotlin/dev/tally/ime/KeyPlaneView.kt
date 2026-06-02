package dev.tally.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.KeyRepeatController
import dev.tally.keyboard.engine.KeyboardHeightPolicy
import dev.tally.keyboard.engine.PointerTrackerPool
import dev.tally.keyboard.engine.ResolvedKey

/**
 * The key bed: a custom Canvas-drawn view that owns key rendering, multitouch dispatch,
 * and the gesture trail.
 *
 * Touch architecture (03 §3.4):
 *   - One [PointerTrackerPool] (pure JVM, unit-testable) manages per-pointer state.
 *   - [onTouchEvent] dispatches by masked action — ACTION_DOWN / ACTION_POINTER_DOWN
 *     create a tracker, ACTION_MOVE updates each active tracker (rollover), ACTION_UP /
 *     ACTION_POINTER_UP emit a key, ACTION_CANCEL tears down all trackers silently.
 *   - Key events are forwarded to [keyListener] so the IME service can route them to
 *     the active InputConnection without coupling this view to the service.
 *
 * Key-preview (T1.3): on ACTION_DOWN a [KeyPreviewPopup] is shown above the pressed key
 * and dismissed on ACTION_UP / ACTION_POINTER_UP / ACTION_CANCEL. Previews are suppressed
 * entirely when [previewMasked] is true (password and other masked input fields).
 *
 * Long-press alternates (T1.4): when a key with [Key.moreKeys] is held beyond
 * [LONG_PRESS_DELAY_MS], the key-preview is replaced by a [LongPressPopup] mini-keyboard.
 * Subsequent MOVE events route to the popup for cell selection; UP commits the highlighted
 * alternate (or the primary key if no cell is highlighted). Normal key commit on UP is
 * suppressed once a long-press tray has fired.
 *
 * Rollover: a finger pressed on key A that slides to key B emits key B on lift,
 * matching the standard keyboard behaviour and fixing the single-pointer dead-end
 * identified in A2-ime.
 */
internal class KeyPlaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Accessibility delegate that exposes each key as a virtual view.
     *
     * Registered via [ViewCompat.setAccessibilityDelegate] in the init block so
     * TalkBack and Switch Access can explore and activate every key. Without this,
     * the Canvas-drawn key plane is one opaque node and blind users cannot type.
     */
    internal val a11yHelper: KeyboardExploreByTouchHelper =
        KeyboardExploreByTouchHelper(
            host = this,
            keyClickListener = { key -> keyListener?.invoke(key) },
        )

    init {
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    /**
     * Called on the main thread whenever a key should be committed.
     *
     * Set by the IME service after view inflation. Receives the resolved [Key]
     * from [currentGeometry] so the service never needs to re-derive it.
     */
    var keyListener: ((Key) -> Unit)? = null

    /**
     * Called on the main thread on every finger-down event that lands on a key.
     *
     * Fires before the key is committed (which happens on UP) so the haptic pulse
     * is synchronous with the finger contact, matching system keyboard behaviour.
     * Set by the IME service to route down events to [KeyFeedback].
     */
    var keyDownFeedbackListener: (() -> Unit)? = null

    /**
     * Called on each accelerating repeat fire while the backspace key is held.
     *
     * The IME service wires this to delete one code point per call.
     * Fires on the main thread; the caller must not block.
     */
    var backspaceRepeatListener: (() -> Unit)? = null

    /**
     * Called when a leftward swipe on the backspace key is detected.
     *
     * The IME service wires this to delete the whole word before the cursor.
     * Only fires once per press regardless of further movement. Fires on the main thread.
     */
    var wordDeleteListener: (() -> Unit)? = null

    /**
     * The current keyboard layout geometry.
     *
     * Set by the IME service (or [KeyboardController]) whenever the active layout
     * changes. Null until the first layout is wired in; touch events are no-ops
     * while null.
     */
    var currentGeometry: KeyGeometry? = null
        set(value) {
            field = value
            a11yHelper.geometry = value
            a11yHelper.invalidateAllKeys()
        }

    /**
     * The active key rows for the current keyboard state.
     *
     * Updated by the IME service to reflect shift/layer changes. Used for rendering
     * and for resolving a [KeyId] back to a [Key] on UP.
     */
    var currentRows: List<KeyRow> = KeyboardLayout.ALPHA_LOWER
        set(value) {
            field = value
            a11yHelper.rows = value
            a11yHelper.invalidateAllKeys()
        }

    /**
     * When true, key-preview popups are suppressed entirely.
     *
     * Set from [FieldPolicy.previewMasked] in [TallyInputMethodService.onStartInputView].
     * True for password fields, no-suggestions fields, and the default-private pre-field state.
     * Showing a magnified bubble of the pressed key while a password is typed would reveal
     * each character to shoulder surfers or screen-recorders.
     */
    var previewMasked: Boolean = true

    private val pool = PointerTrackerPool()

    // Pixel threshold for the swipe-left-on-delete word-delete gesture, derived from
    // the display density so the gesture feels the same across screen densities.
    private val SWIPE_LEFT_THRESHOLD_PX: Float =
        SWIPE_LEFT_THRESHOLD_DP * resources.displayMetrics.density

    // Pre-allocated; color is refreshed from the theme token at the start of each draw so
    // a light↔dark switch takes effect on the next frame without recreating the view.
    private val bgPaint = Paint()

    // One preview popup per pointer slot. The map is keyed by pointerId so
    // simultaneous presses each get their own bubble.
    private val previews: MutableMap<Int, KeyPreviewPopup> = HashMap()

    // One long-press popup per pointer slot. Keyed by pointerId; lazily allocated.
    private val longPressPopups: MutableMap<Int, LongPressPopup> = HashMap()

    // Pointers that have an active (fired) long-press tray. Once a pointer enters this set
    // its normal key-commit on UP is suppressed; the alternate (or primary fallback) commits
    // when the finger lifts.
    private val longPressActive: MutableSet<Int> = HashSet()

    // The primary Key that triggered each active long-press (used for the fallback commit).
    private val longPressSourceKey: MutableMap<Int, Key> = HashMap()

    // Pending (not-yet-fired) long-press timers, keyed by pointerId.
    private val pendingLongPress: MutableMap<Int, Runnable> = HashMap()

    private val handler = Handler(Looper.getMainLooper())

    // ── Backspace repeat + swipe-left (T1.9) ──────────────────────────────────

    // Schedule that produces each repeat interval.
    private val repeatController = KeyRepeatController()

    // Runnable that fires one repeat tick and re-arms itself.
    private var repeatRunnable: Runnable? = null

    // pointerId of the pointer that pressed backspace, or -1 when not held.
    private var backspacePointerId: Int = NO_ACTIVE_POINTER

    // X position at the moment the backspace pointer went down, in view pixels.
    private var backspaceDownX: Float = 0f

    // True once the swipe-left gesture has been detected for the current press.
    // Prevents re-triggering on further movement and blocks the normal repeat.
    private var wordDeleteFired: Boolean = false

    // ── Measurement ───────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val dm = resources.displayMetrics
        val h = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            density        = dm.density,
            rowCount       = currentRows.size.coerceAtLeast(DEFAULT_ROW_COUNT),
        )
        setMeasuredDimension(w, h)
    }

    // Theme token provider — resolves colors from the current Context configuration on each access.
    private val theme = dev.tally.design.KeyTheme(context)

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        bgPaint.color = theme.keyboardBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    // ── Touch dispatch ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val geometry = currentGeometry ?: return false  // no layout yet — discard

        val maskedAction = event.actionMasked
        val actionIndex  = event.actionIndex

        when (maskedAction) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val keyId = pool.onDown(pointerId, x, y, geometry)

                val resolvedKey: ResolvedKey? = if (keyId != null) geometry.keyById(keyId) else null

                // Haptic + sound feedback fires on finger contact, before the commit on UP.
                if (keyId != null) {
                    keyDownFeedbackListener?.invoke()
                }

                // Key-preview: show for labelled character keys in unmasked fields.
                if (!previewMasked && resolvedKey != null && resolvedKey.keyDef.label.isNotEmpty()) {
                    val popup = previews.getOrPut(pointerId) { KeyPreviewPopup(context) }
                    popup.show(resolvedKey, this)
                }

                // Long-press: arm a timer for keys that carry alternates.
                val layoutKey: Key? = if (keyId != null) resolveKey(keyId) else null
                if (layoutKey != null && layoutKey.moreKeys.isNotEmpty() && resolvedKey != null) {
                    armLongPress(pointerId, layoutKey, resolvedKey)
                }

                // Backspace repeat: arm accelerating fires while the key is held.
                if (layoutKey?.code == KeyCode.Backspace) {
                    armBackspaceRepeat(pointerId, x)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // ACTION_MOVE may batch samples for all active pointers.
                for (pointerIdx in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIdx)
                    // Process historical samples first so the slide path is complete.
                    for (histIdx in 0 until event.historySize) {
                        pool.onMove(
                            pointerId,
                            event.getHistoricalX(pointerIdx, histIdx),
                            event.getHistoricalY(pointerIdx, histIdx),
                        )
                    }
                    val x = event.getX(pointerIdx)
                    pool.onMove(pointerId, x, event.getY(pointerIdx))

                    // Route x-coordinate to an active long-press tray for cell highlighting.
                    if (pointerId in longPressActive) {
                        longPressPopups[pointerId]?.onMove(x)
                    }

                    // Swipe-left on backspace: detect threshold crossing for word delete.
                    if (pointerId == backspacePointerId && !wordDeleteFired) {
                        val deltaX = x - backspaceDownX
                        if (deltaX < -SWIPE_LEFT_THRESHOLD_PX) {
                            wordDeleteFired = true
                            cancelBackspaceRepeat()
                            wordDeleteListener?.invoke()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                cancelPendingLongPress(pointerId)
                dismissPreview(pointerId)
                if (pointerId == backspacePointerId) {
                    cancelBackspaceRepeat()
                }

                if (pointerId in longPressActive) {
                    // Long-press was triggered for this pointer. Commit the highlighted alternate
                    // if one is selected; otherwise fall back to the primary key.
                    val lpp      = longPressPopups[pointerId]
                    val altIndex = lpp?.selectedIndex ?: -1
                    val base     = longPressSourceKey[pointerId]

                    if (altIndex >= 0 && base != null) {
                        val altLabel = base.moreKeys[altIndex]
                        // Alternates are always single characters in the current data.
                        val altKey = Key(
                            code  = KeyCode.Char(altLabel[0]),
                            label = altLabel,
                        )
                        keyListener?.invoke(altKey)
                    } else if (base != null) {
                        // Lifted without selecting: commit the primary.
                        keyListener?.invoke(base)
                    } else if (altIndex >= 0) {
                        // altIndex is set but base is null — longPressSourceKey did not contain
                        // this pointerId. This is a race with tearDownAllLongPresses on ACTION_CANCEL.
                        // Drop silently but log so the race is traceable during debugging.
                        Log.w(TAG, "long-press UP: no source key for pointer $pointerId (altIndex=$altIndex); dropping alternate")
                    }

                    tearDownLongPress(pointerId)
                } else {
                    val keyId = pool.onUp(pointerId)
                    if (keyId != null) {
                        resolveKey(keyId)?.let { key -> keyListener?.invoke(key) }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Parent view has stolen the gesture (e.g., a scroll). Drop everything silently.
                cancelAllPendingLongPresses()
                tearDownAllLongPresses()
                dismissAllPreviews()
                cancelBackspaceRepeat()
                pool.onCancel()
            }
        }

        return true
    }

    // ── Accessibility ─────────────────────────────────────────────────────────

    /**
     * Routes hover (accessibility explore-by-touch) events to the virtual view tree.
     *
     * TalkBack sends hover events as the user drags a finger across the screen in
     * touch-exploration mode. Delegating to [a11yHelper] lets TalkBack announce each
     * key's content description as the finger crosses its bounds, and triggers the
     * lift-to-type action when the finger lifts.
     *
     * Must be overridden here — the framework calls [View.dispatchHoverEvent] directly
     * and does not go through the accessibility delegate for this path.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11yHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // ── Key resolution ────────────────────────────────────────────────────────

    /**
     * Maps a [KeyId] back to the [Key] in the current layout rows.
     *
     * Returns null if the ID is out of range (defensive; should not happen when
     * [currentRows] and [currentGeometry] are kept in sync).
     */
    private fun resolveKey(keyId: KeyId): Key? {
        val row = currentRows.getOrNull(keyId.rowIndex) ?: return null
        return row.keys.getOrNull(keyId.keyIndex)
    }

    // ── Long-press lifecycle ──────────────────────────────────────────────────

    private fun armLongPress(pointerId: Int, key: Key, resolved: ResolvedKey) {
        val r = Runnable {
            pendingLongPress.remove(pointerId)
            // Dismiss the preview bubble; the tray takes over the visual.
            dismissPreview(pointerId)

            longPressActive += pointerId
            longPressSourceKey[pointerId] = key

            val lpp = longPressPopups.getOrPut(pointerId) { LongPressPopup(context) }
            lpp.show(resolved, this, key.moreKeys)
        }
        pendingLongPress[pointerId] = r
        handler.postDelayed(r, LONG_PRESS_DELAY_MS)
    }

    private fun cancelPendingLongPress(pointerId: Int) {
        pendingLongPress.remove(pointerId)?.let { handler.removeCallbacks(it) }
    }

    private fun cancelAllPendingLongPresses() {
        pendingLongPress.values.forEach { handler.removeCallbacks(it) }
        pendingLongPress.clear()
    }

    private fun tearDownLongPress(pointerId: Int) {
        longPressPopups[pointerId]?.dismiss()
        longPressActive -= pointerId
        longPressSourceKey.remove(pointerId)
        pool.onUp(pointerId) // drain the tracker that was left open
    }

    private fun tearDownAllLongPresses() {
        longPressPopups.values.forEach { it.dismiss() }
        longPressActive.clear()
        longPressSourceKey.clear()
    }

    // ── Backspace repeat lifecycle ────────────────────────────────────────────

    private fun armBackspaceRepeat(pointerId: Int, downX: Float) {
        cancelBackspaceRepeat()  // guard against a stale press
        backspacePointerId = pointerId
        backspaceDownX     = downX
        wordDeleteFired    = false
        repeatController.reset()
        scheduleNextRepeat()
    }

    private fun scheduleNextRepeat() {
        val delay = repeatController.nextIntervalMs()
        val r = Runnable {
            repeatRunnable = null
            if (!wordDeleteFired) {
                backspaceRepeatListener?.invoke()
                scheduleNextRepeat()
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun cancelBackspaceRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable      = null
        backspacePointerId  = NO_ACTIVE_POINTER
        wordDeleteFired     = false
    }

    // ── Preview lifecycle helpers ─────────────────────────────────────────────

    private fun dismissPreview(pointerId: Int) {
        previews[pointerId]?.dismiss()
    }

    private fun dismissAllPreviews() {
        previews.values.forEach { it.dismiss() }
    }

    // ── Test seams ────────────────────────────────────────────────────────────

    /** Exposes the pool size for testing without exposing the pool itself. */
    internal fun activePointerCount(): Int = pool.size

    /**
     * Returns true if a key-preview popup is currently visible for [pointerId].
     * Exposed for test assertions only.
     */
    internal fun isPreviewShowing(pointerId: Int): Boolean =
        previews[pointerId]?.isShowing == true

    /**
     * Returns true if a long-press alternate tray is currently visible for [pointerId].
     * Exposed for test assertions only.
     */
    internal fun isLongPressShowing(pointerId: Int): Boolean =
        longPressPopups[pointerId]?.isShowing == true

    /**
     * Returns the currently highlighted alternate index in the long-press tray, or -1.
     * Exposed for test assertions only.
     */
    internal fun longPressSelectedIndex(pointerId: Int): Int =
        longPressPopups[pointerId]?.selectedIndex ?: -1

    private companion object {
        const val TAG = "KeyPlaneView"

        // Fallback row count used when currentRows is empty (e.g. before the first layout sync).
        // Matches the standard QWERTY row count: letter rows + bottom action row.
        const val DEFAULT_ROW_COUNT = 4

        // Standard long-press interval for keyboard key alternates. Android's own ViewConfiguration
        // uses 400 ms for long-press; matching that provides a consistent feel.
        const val LONG_PRESS_DELAY_MS = 400L

        // Minimum leftward pixel travel required to trigger the swipe-left word-delete gesture
        // on the backspace key. 40 dp at 2× density = 80 px; the constant is in raw pixels so
        // the view's initialiser can scale it from its display metrics once, below.
        // The value here (in dp) is converted to px at construction via displayMetrics.density.
        const val SWIPE_LEFT_THRESHOLD_DP = 40f

        // Sentinel used for backspacePointerId when no backspace pointer is active.
        const val NO_ACTIVE_POINTER = -1
    }
}
