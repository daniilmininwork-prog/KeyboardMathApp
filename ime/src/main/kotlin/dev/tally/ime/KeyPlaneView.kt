package dev.tally.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import dev.tally.keyboard.engine.CursorController
import dev.tally.keyboard.engine.BackspaceSpeed
import dev.tally.keyboard.engine.FormFactorMode
import dev.tally.keyboard.engine.FormFactorTransform
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.KeyRepeatController
import dev.tally.keyboard.engine.KeyboardHeightPolicy
import dev.tally.keyboard.engine.LongPressDelay
import dev.tally.keyboard.engine.PointerTrackerPool
import dev.tally.keyboard.engine.ResolvedKey
import dev.tally.keyboard.engine.SpecialCode

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
 * [longPressDelayMs], the key-preview is replaced by a [LongPressPopup] mini-keyboard.
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
            // Accessibility activations have no MotionEvent; use the current uptime, which can
            // never fall inside the double-space window of a real preceding touch.
            keyClickListener = { key -> keyListener?.invoke(key, android.os.SystemClock.uptimeMillis()) },
        )

    init {
        ViewCompat.setAccessibilityDelegate(this, a11yHelper)
    }

    /**
     * Called on the main thread whenever a key should be committed.
     *
     * Set by the IME service after view inflation. Receives the resolved [Key]
     * from [currentGeometry] so the service never needs to re-derive it, plus the
     * touch-up event time (ms, [android.os.SystemClock.uptimeMillis] clock) of the
     * gesture that committed it. The event time drives the double-space-to-period
     * window so it reflects finger timing rather than dispatch latency. Non-touch
     * activations (accessibility) pass the current uptime, which never pairs.
     */
    var keyListener: ((Key, Long) -> Unit)? = null

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
     * Called for each discrete cursor-step produced by a space-bar swipe gesture (T4.4).
     *
     * [steps] is negative for leftward (cursor back) and positive for rightward (cursor
     * forward). The magnitude is the number of positions to advance in one callback; the
     * IME service must fire one [android.view.KeyEvent] (or [InputConnection.setSelection]
     * delta) per unit.
     *
     * [select] is true when the gesture should extend the selection rather than move the
     * bare cursor — set by the IME service based on the current shift state.
     *
     * Only called when [steps] != 0.  Fires on the main thread.
     */
    var cursorStepListener: ((steps: Int, select: Boolean) -> Unit)? = null

    /**
     * Whether the current shift state means the space-swipe extends selection.
     *
     * Set by [TallyInputMethodService] in sync with [KeyboardController.currentShiftState].
     * The cursor controller reads this flag via [CursorController.selectionMode].
     */
    var cursorSelectMode: Boolean = false
        set(value) {
            field = value
            cursorController.selectionMode = value
        }

    // ── Form-factor (T4.6) ────────────────────────────────────────────────────

    /**
     * Active form-factor mode; triggers a layout pass and redraw when changed.
     *
     * Set by [TallyInputMethodService] from [TallyPreferences.formFactorKey] on each
     * [onStartInputView] so that settings changes applied while the keyboard was hidden
     * take effect without a service restart.
     */
    var formFactor: FormFactorMode = FormFactorMode.NORMAL
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Whether the one-handed keyboard aligns to the right edge. */
    var oneHandedRight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (formFactor == FormFactorMode.ONE_HANDED) {
                requestLayout()
            }
        }

    /** Horizontal pixel offset for FLOATING mode. Clamped inside [FormFactorTransform]. */
    var floatingOffsetX: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (formFactor == FormFactorMode.FLOATING) requestLayout()
        }

    /** Vertical pixel offset for FLOATING mode. */
    var floatingOffsetY: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (formFactor == FormFactorMode.FLOATING) requestLayout()
        }

    /**
     * The most recently resolved [FormFactorTransform.Result].
     *
     * Computed during [onMeasure] from the current [formFactor] and display metrics.
     * Read by [TallyInputMethodService.onComputeInsets] to determine whether to claim
     * bottom insets (FLOATING mode claims none) and what height to report.
     */
    internal var lastTransform: FormFactorTransform.Result? = null
        private set

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
            // A shift/layer swap keeps the same viewport size, so onSizeChanged may not fire; route
            // through requestLayout so the geometry is rebuilt in onLayout against the new rows.
            // Deferring (rather than rebuilding inline) keeps the unit tests' explicitly-injected
            // currentGeometry authoritative — requestLayout does not run a synchronous layout pass
            // in Robolectric, whereas production schedules one that lands in onLayout below.
            requestLayout()
            invalidate()
        }

    /**
     * When true, the key-preview bubble is shown in redacted mode (a neutral dot, no glyph)
     * so secure fields keep tactile feedback without leaking the typed character.
     *
     * Set from [FieldPolicy.previewMasked] in [TallyInputMethodService.onStartInputView],
     * which runs on every field entry — so the policy, not this default, governs once a field
     * is focused. The default is fail-open (false: full glyph preview) so the very first
     * pre-field state still feels alive; the masked bubble only appears for password and
     * no-suggestion fields, where a magnified glyph would expose each character to shoulder
     * surfers or screen-recorders.
     */
    var previewMasked: Boolean = false

    /**
     * Whether each key's primary long-press alternate is drawn as a small hint glyph in the
     * key's upper corner, like the Samsung/Gboard keycap hints.
     *
     * Set from [TallyPreferences.altCharHints] in [TallyInputMethodService.onStartInputView],
     * which runs on every field entry — so a toggle made while the keyboard was hidden takes
     * effect on the next focus without a service restart. Defaults to false to match Samsung's
     * stock keyboard, which ships the hint hidden. A change requests a redraw so the hints
     * appear/disappear on the next frame without rebuilding the view.
     */
    var altCharHints: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Multiplier applied to the on-key glyph sizes at draw time (Stage 3).
     *
     * Set from [TallyPreferences.keyFontScale] in [TallyInputMethodService.onStartInputView] so a
     * change made while the keyboard was hidden takes effect on the next focus without a service
     * restart. Applied to the text paints inside [onDraw] only — the key rects and overall
     * footprint ([buildGeometry]) are unaffected, so touch targets never move with the font size.
     * Defaults to 1.0, which reproduces the historical hardcoded sizes exactly. A change requests a
     * redraw so the new size appears on the next frame without rebuilding the view.
     */
    var keyFontScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val pool = PointerTrackerPool()

    // Pixel threshold for the swipe-left-on-delete word-delete gesture, derived from
    // the display density so the gesture feels the same across screen densities.
    private val SWIPE_LEFT_THRESHOLD_PX: Float =
        SWIPE_LEFT_THRESHOLD_DP * resources.displayMetrics.density

    // ── Space-bar cursor control (T4.4) ───────────────────────────────────────

    // Density-scaled cursor controller. Dead zone and step size are in view-pixel space so the
    // gesture feels identical on all screen densities.
    private val cursorController: CursorController = run {
        val density = resources.displayMetrics.density
        CursorController(
            deadZonePx = CursorController.DEFAULT_DEAD_ZONE_PX * density,
            stepPx     = CursorController.DEFAULT_STEP_PX * density,
        )
    }

    // pointerId of the pointer that pressed the space key, or NO_ACTIVE_POINTER.
    private var spacePointerId: Int = NO_ACTIVE_POINTER

    // Pre-allocated to avoid in-draw allocation. Colors are refreshed from theme tokens at the
    // start of each onDraw so a light↔dark switch takes effect on the next frame without
    // recreating the view. Mirrors TallyKeyboardView's paint set so rendering matches the
    // reference key bed exactly.
    private val bgPaint         = Paint()
    private val keyPaint        = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    // Alt-character hint glyph: a smaller, dimmed copy of the key-text colour drawn in the corner.
    // Colour and size are refreshed per-frame in onDraw so a theme/text-size change is reflected
    // without recreating the view. End-aligned so it sits flush in the upper-trailing corner.
    private val hintPaint       = Paint(Paint.ANTI_ALIAS_FLAG)
    // Per-key border stroke, drawn only when the active theme requests it (high-contrast presets).
    // Stroke width is fixed in dp so the outline reads at any density; colour is refreshed per-frame.
    private val borderPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    // KeyIds with a finger currently down on them. Populated on ACTION_DOWN / ACTION_POINTER_DOWN
    // and drained on UP / POINTER_UP / CANCEL; onDraw paints these with a pressed tint so a held
    // key reads as pressed. A set (not a single field) so simultaneous fingers each show feedback.
    private val pressedKeys = HashSet<KeyId>()

    // The KeyId currently tinted for each active pointer, keyed by pointerId. Lets UP and MOVE
    // remove/swap the exact entry a finger owns, so rollover (finger slides A→B) moves the tint
    // and two fingers on one key don't clear each other's press. Kept in lock-step with pressedKeys.
    private val pressedKeyByPointer: MutableMap<Int, KeyId> = HashMap()

    // Half of the inter-key gaps. These are visual insets applied at draw time only (Step 2);
    // the geometry stores full key rects so touch targets fill the gap and never shrink
    // (see PointerTrackerPool hit-test, which uses ResolvedKey.contains on the full rect).
    //
    // PHASE 1b sizing polish: the gaps were tightened (6dp→5dp horizontal, 8dp→6dp vertical) so
    // the drawn face fills more of its hit rect. The previous gaps left a wide dead-looking margin
    // around each key, making the keyboard feel cramped even though the touch targets were large;
    // a smaller inset makes what the user sees agree more closely with what they can tap.
    private val hGapHalf = dpToPx(2.5f) // half of the 5dp horizontal gap between keys
    private val vGapHalf = dpToPx(3f)   // half of the 6dp vertical gap between rows

    // Key-face metrics, matching TallyKeyboardView so the two views render identically.
    // PHASE 1b: corner radius raised 8dp→10dp for a softer, more modern key-cap silhouette.
    private val cornerRadius      = dpToPx(10f)
    private val keyTextSizePx     = spToPx(18f)
    private val specialTextSizePx = spToPx(14f)
    // Corner hint glyph size: ~0.55× the main label, matching the small keycap hints on Samsung/Gboard.
    private val hintTextSizePx    = keyTextSizePx * HINT_TEXT_SCALE
    // Padding from the key face's drawn edge to the hint glyph, so it never touches the rounded corner.
    private val hintInsetPx       = dpToPx(3f)

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

    // Schedule that produces each repeat interval. Reassigned (not mutated) when the user
    // changes the backspace-speed preference so the next press picks up the new timing; a fresh
    // controller also starts the accelerate schedule cleanly from the new initial delay.
    private var repeatController = KeyRepeatController()

    /**
     * Backspace key-repeat speed. Set by [TallyInputMethodService] from
     * [TallyPreferences.backspaceSpeedKey] on each [onStartInputView] so a settings change made
     * while the keyboard was hidden takes effect on the next field focus.
     *
     * Assigning rebuilds [repeatController] with the speed's initial-delay / step / floor; a
     * press already in flight keeps the controller it started with (the rebuild only affects the
     * next [armBackspaceRepeat]).
     */
    var backspaceSpeed: BackspaceSpeed = BackspaceSpeed.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                repeatController = value.newController()
            }
        }

    /**
     * Touch-and-hold delay before a key's long-press alternate tray fires. Set by
     * [TallyInputMethodService] from [TallyPreferences.longPressDelayKey] on each
     * [onStartInputView]. Read at arm time in [armLongPress], so a change applies to the next
     * press without recreating the view.
     */
    var longPressDelayMs: Long = LongPressDelay.DEFAULT.delayMs

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
        val dm = resources.displayMetrics
        // Use the measure-spec width as the viewport width so the view honours
        // whatever width the parent assigns (e.g. 1080px in tests, full screen in prod).
        val specW = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: dm.widthPixels
        val transform = FormFactorTransform.resolve(
            mode              = formFactor,
            viewportWidthPx   = specW,
            viewportHeightPx  = dm.heightPixels,
            density           = dm.density,
            rowCount          = currentRows.size.coerceAtLeast(DEFAULT_ROW_COUNT),
            oneHandedRight    = oneHandedRight,
            floatingOffsetXPx = floatingOffsetX,
            floatingOffsetYPx = floatingOffsetY,
        )
        lastTransform = transform
        setMeasuredDimension(transform.widthPx, transform.heightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Build geometry from the final pixel bounds. onLayout (not onSizeChanged) is used because a
        // shift/layer swap changes currentRows without changing the view size — onSizeChanged would
        // not fire then, but onLayout runs on every requestLayout-driven pass, so the geometry stays
        // in lock-step with currentRows. FormFactorTransform has already sized the view by this point.
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0) {
            currentGeometry = buildGeometry(w, h, currentRows)
        }
    }

    // Theme token provider — resolves colors from the current Context configuration on each access.
    // Exposed internally so TallyInputMethodService can register it with TallyThemeManager for
    // dynamic color fan-out without coupling this view to the manager directly.
    internal val theme = dev.tally.design.KeyTheme(context)

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Refresh every paint from the theme tokens before drawing so a light↔dark switch (or a
        // dynamic-color update) is reflected on the next frame without recreating the view.
        bgPaint.color         = theme.keyboardBg
        keyPaint.color        = theme.keyBg
        specialKeyPaint.color = theme.keySpecialBg
        textPaint.color       = theme.keyText
        // Pressed tint: blend the key text colour over the key background so the held key darkens
        // (light theme) or lightens (dark theme) toward its own glyph colour, staying on-theme for
        // either palette. Derived per-frame so a light↔dark switch is reflected without a recreate.
        pressedPaint.color    = blendTint(theme.keyBg, theme.keyText, PRESSED_TINT_FRACTION)
        // Hint glyph: key-text colour at reduced alpha so the alternate reads as secondary, never
        // competing with the main label. Refreshed here so a light↔dark switch recolours it too.
        hintPaint.color = theme.keyText
        hintPaint.alpha = HINT_ALPHA
        // Scale the hint glyph by the same user font-scale as the main labels so the keycap hint
        // stays proportional. Applied here (draw time) so the key rects are untouched.
        hintPaint.textSize = hintTextSizePx * keyFontScale
        // High-contrast themes outline each key for legibility; refreshed per-frame so a theme
        // change (incl. toggling a high-contrast preset) is reflected without recreating the view.
        val drawBorders = theme.drawKeyBorders
        borderPaint.color = theme.keyBorder

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // In SPLIT mode, overdraw the central gap with a darkened strip so the keyboard
        // visually appears as two separate halves. The touch areas are unchanged — the gap
        // simply has no keys mapped to it in the geometry.
        if (formFactor == FormFactorMode.SPLIT && width > 0) {
            val gapCentreX = width / 2f
            val halfGap = SPLIT_GAP_PX * resources.displayMetrics.density / 2f
            // Darken/lighten the keyboard background slightly to produce a neutral groove.
            val base = theme.keyboardBg
            bgPaint.color = android.graphics.Color.argb(
                0xFF,
                ((android.graphics.Color.red(base) * SPLIT_GAP_DIM_FACTOR).toInt()).coerceIn(0, 255),
                ((android.graphics.Color.green(base) * SPLIT_GAP_DIM_FACTOR).toInt()).coerceIn(0, 255),
                ((android.graphics.Color.blue(base) * SPLIT_GAP_DIM_FACTOR).toInt()).coerceIn(0, 255),
            )
            canvas.drawRect(
                gapCentreX - halfGap, 0f,
                gapCentreX + halfGap, height.toFloat(),
                bgPaint,
            )
        }

        // Draw each key from the geometry — the same rects PointerTrackerPool hit-tests against,
        // so what the user sees and what they touch are guaranteed to agree. Drawing straight off
        // the resolved KeyDef (rather than re-deriving from currentRows per key) keeps the loop
        // allocation-free and reads the same data the touch/a11y paths use.
        val geometry = currentGeometry ?: return
        for (resolvedKey in geometry.keys) {
            val keyDef = resolvedKey.keyDef

            // Visual inset only: shrink the drawn face by the inter-key gap so neighbouring keys
            // read as separate. The full rect remains the touch target (geometry is uninset), so
            // there are no dead gaps between keys.
            val left   = resolvedKey.left + hGapHalf
            val top    = resolvedKey.top + vGapHalf
            val right  = resolvedKey.right - hGapHalf
            val bottom = resolvedKey.bottom - vGapHalf

            // A held key fills its FULL hit rect (the un-inset geometry rect) with the pressed
            // tint so feedback covers the entire touch target, not just the smaller drawn face.
            // The resting face is skipped for that key so the tint reads cleanly rather than being
            // overpainted by the opaque face. Resting keys draw their inset face as before.
            if (resolvedKey.id in pressedKeys) {
                canvas.drawRoundRect(
                    resolvedKey.left, resolvedKey.top, resolvedKey.right, resolvedKey.bottom,
                    cornerRadius, cornerRadius, pressedPaint,
                )
            } else {
                val paint = if (keyDef.isSpecial) specialKeyPaint else keyPaint
                canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint)
            }

            // High-contrast border: stroke the inset face outline so every key reads as a distinct
            // shape against the background. Drawn over the resting/pressed fill but under the label.
            // The stroke is inset by half its width so the outline stays inside the face rect and
            // does not visually merge with the neighbouring key's border across the gap.
            if (drawBorders) {
                val inset = borderPaint.strokeWidth / 2f
                canvas.drawRoundRect(
                    left + inset, top + inset, right - inset, bottom - inset,
                    cornerRadius, cornerRadius, borderPaint,
                )
            }

            // Skip the Space label (it stays blank, per the reference) and any code-only key.
            if (keyDef.code != SpecialCode.SPACE && keyDef.label.isNotEmpty()) {
                // Apply the user font-scale at draw time only: the base sp size is multiplied here so
                // the glyph grows/shrinks while the key rect (and therefore the touch target) is
                // unchanged. specialTextSizePx covers the smaller labels on special keys.
                textPaint.textSize = (if (keyDef.isSpecial) specialTextSizePx else keyTextSizePx) * keyFontScale
                val textY = resolvedKey.centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(keyDef.label, resolvedKey.centerX, textY, textPaint)
            }

            // Alt-character keycap hint: when enabled, draw the primary long-press alternate as a
            // small dimmed glyph in the upper corner so the long-press char is discoverable without
            // holding (Samsung/Gboard parity). Drawn last so it sits above the face; clipped to the
            // inset face so it never spills past the rounded edge or overlaps a neighbour.
            if (altCharHints && keyDef.moreKeys.isNotEmpty()) {
                drawAltHint(canvas, keyDef.moreKeys.first(), left, top, right)
            }
        }
    }

    /**
     * Draws [glyph] as a small dimmed hint in the upper corner of a key face bounded by
     * [faceLeft]/[faceTop]/[faceRight] (the gap-inset drawn face, not the full hit rect).
     *
     * The corner is upper-trailing in LTR (right) and upper-leading in RTL (left), matching the
     * physical position the long-press tray opens toward. The glyph is clipped to the face rect so
     * a wide alternate can never bleed past the rounded edge into the neighbouring key.
     */
    private fun drawAltHint(canvas: Canvas, glyph: String, faceLeft: Float, faceTop: Float, faceRight: Float) {
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (isRtl) {
            hintPaint.textAlign = Paint.Align.LEFT
        } else {
            hintPaint.textAlign = Paint.Align.RIGHT
        }
        val x = if (isRtl) faceLeft + hintInsetPx else faceRight - hintInsetPx
        // Baseline just below the face top, offset by the glyph ascent so the cap sits inside the face.
        val y = faceTop + hintInsetPx - hintPaint.ascent()

        val saved = canvas.save()
        canvas.clipRect(faceLeft, faceTop, faceRight, faceTop + (y - faceTop) + hintPaint.descent())
        canvas.drawText(glyph, x, y, hintPaint)
        canvas.restoreToCount(saved)
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

                // Visual press feedback: mark the key down and repaint so onDraw tints its hit rect.
                if (keyId != null) {
                    setPressedKey(pointerId, keyId)
                    invalidate()
                }

                // Haptic + sound feedback fires on finger contact, before the commit on UP.
                if (keyId != null) {
                    keyDownFeedbackListener?.invoke()
                }

                // Key-preview: show for labelled character keys. In masked fields the bubble still
                // appears (so the press feels tactile) but renders a neutral dot instead of the
                // glyph, so a password character is never magnified above the finger.
                if (resolvedKey != null && resolvedKey.keyDef.label.isNotEmpty()) {
                    val popup = previews.getOrPut(pointerId) { KeyPreviewPopup(context) }
                    popup.show(resolvedKey, this, redacted = previewMasked)
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

                // Space-bar cursor control (T4.4): arm the controller for the pointer that
                // lands on the space key. Only one space pointer is tracked at a time.
                if (layoutKey?.code == KeyCode.Space && spacePointerId == NO_ACTIVE_POINTER) {
                    spacePointerId = pointerId
                    cursorController.selectionMode = cursorSelectMode
                    cursorController.onSpaceDown(x)
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
                    val movedKeyId = pool.onMove(pointerId, x, event.getY(pointerIdx))

                    // Rollover: if the finger slid onto a different key, move its pressed tint so the
                    // highlight tracks the finger. Only the pointer that owns the press is touched, so
                    // a second finger resting on the old key keeps it lit. Repaint only on a real swap.
                    if (pointerId in pressedKeyByPointer && movedKeyId != pressedKeyByPointer[pointerId]) {
                        if (movedKeyId != null) {
                            setPressedKey(pointerId, movedKeyId)
                        } else {
                            clearPressedKey(pointerId)
                        }
                        invalidate()
                    }

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

                    // Space-bar cursor-control: feed move samples and fire step callbacks
                    // for each discrete cursor position crossed (T4.4).
                    if (pointerId == spacePointerId) {
                        val steps = cursorController.onMove(x)
                        if (steps != 0) {
                            cursorStepListener?.invoke(steps, cursorController.selectionMode)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                cancelPendingLongPress(pointerId)
                dismissPreview(pointerId)
                // Release this pointer's pressed tint and repaint so the key returns to rest.
                if (clearPressedKey(pointerId)) invalidate()
                if (pointerId == backspacePointerId) {
                    cancelBackspaceRepeat()
                }

                // Space-bar cursor-control: resolve and clear the cursor gesture before the
                // normal key-commit logic so we know whether to suppress the space character.
                val spaceGestureConsumed = if (pointerId == spacePointerId) {
                    spacePointerId = NO_ACTIVE_POINTER
                    cursorController.onUp()   // returns true ↔ gesture was active
                } else false

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
                        keyListener?.invoke(altKey, event.eventTime)
                    } else if (base != null) {
                        // Lifted without selecting: commit the primary.
                        keyListener?.invoke(base, event.eventTime)
                    } else if (altIndex >= 0) {
                        // altIndex is set but base is null — longPressSourceKey did not contain
                        // this pointerId. This is a race with tearDownAllLongPresses on ACTION_CANCEL.
                        // Drop silently but log so the race is traceable during debugging.
                        Log.w(TAG, "long-press UP: no source key for pointer $pointerId (altIndex=$altIndex); dropping alternate")
                    }

                    tearDownLongPress(pointerId)
                } else {
                    val keyId = pool.onUp(pointerId)
                    if (keyId != null && !spaceGestureConsumed) {
                        resolveKey(keyId)?.let { key -> keyListener?.invoke(key, event.eventTime) }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Parent view has stolen the gesture (e.g., a scroll). Drop everything silently.
                cancelAllPendingLongPresses()
                tearDownAllLongPresses()
                dismissAllPreviews()
                cancelBackspaceRepeat()
                cancelSpaceCursorGesture()
                clearAllPressedKeys()
                invalidate()
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

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * Builds the pixel [KeyGeometry] for [rows] within a [w]×[h] viewport.
     *
     * Layout model mirrors [TallyKeyboardView.recomputeRects]: the row width is split into ten
     * equal base units, each key occupies [Key.widthUnits] of them, and short rows are centred via
     * [KeyRow.startOffsetUnits]. RTL layouts mirror key order so the data-file-rightmost key sits on
     * the physical right.
     *
     * The rects stored here are the FULL key cells with no gap inset — they are the hit-test targets
     * consumed by [PointerTrackerPool], so insetting them would shrink the touch area. The visual
     * inter-key gap ([hGapHalf]/[vGapHalf]) is applied at draw time only.
     *
     * Each [KeyId] is `(rowIndex, keyIndex)` in visual left-to-right order, matching exactly what
     * [resolveKey] unpacks from [currentRows] so geometry and rows stay byte-compatible.
     */
    private fun buildGeometry(w: Int, h: Int, rows: List<KeyRow>): KeyGeometry {
        val rowH = if (rows.isEmpty()) h.toFloat() else h.toFloat() / rows.size
        val unit = w.toFloat() / 10f   // base unit = 1/10 of the row width

        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val resolved = ArrayList<ResolvedKey>()

        rows.forEachIndexed { rowIdx, row ->
            val top = rowH * rowIdx
            val bottom = rowH * (rowIdx + 1)

            // keyIndex must reflect visual position, so mirror the key order itself in RTL rather
            // than just the x-arithmetic — the index then matches the visual left-to-right slot.
            val keysInOrder = if (isRtl) row.keys.reversed() else row.keys
            var x = row.startOffsetUnits * unit

            keysInOrder.forEachIndexed { keyIdx, key ->
                val keyW = key.widthUnits * unit
                val left = x
                val right = x + keyW
                x += keyW
                resolved += ResolvedKey(
                    id = KeyId(rowIdx, keyIdx),
                    keyDef = key.toKeyDef(),
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                )
            }
        }

        return KeyGeometry(
            viewportWidth = w,
            viewportHeight = h,
            rowHeight = rowH,
            keys = resolved,
        )
    }

    /**
     * Projects an ime [Key] onto the engine's [KeyDef] carried inside [ResolvedKey].
     *
     * The geometry layer is Android-free and speaks [KeyDef], so the label/moreKeys/isSpecial
     * the touch and a11y paths read off the resolved key are copied here. The integer [KeyDef.code]
     * is the primary code point for character keys and the matching [SpecialCode] sentinel otherwise.
     */
    private fun Key.toKeyDef(): KeyDef = KeyDef(
        code = when (val c = code) {
            is KeyCode.Char       -> c.value.code
            KeyCode.Backspace     -> SpecialCode.DELETE
            KeyCode.Enter         -> SpecialCode.ENTER
            KeyCode.Space         -> SpecialCode.SPACE
            KeyCode.Shift         -> SpecialCode.SHIFT
            KeyCode.SwitchToNumeric -> SpecialCode.NUMERIC
            KeyCode.SwitchToAlpha   -> SpecialCode.ALPHA
            KeyCode.SwitchToSymbols -> SpecialCode.SYMBOLS
            KeyCode.ToggleNumberRow -> SpecialCode.NUMBER_ROW_TOGGLE
            KeyCode.Globe         -> SpecialCode.GLOBE
            KeyCode.Voice         -> SpecialCode.VOICE
        },
        label = label,
        moreKeys = moreKeys,
        width = widthUnits,
        isSpecial = isSpecial,
    )

    /**
     * Returns [base] with [fraction] of [over] blended into each RGB channel (alpha forced opaque).
     *
     * Used to derive the pressed-key tint from theme tokens without a dedicated color resource, so
     * the feedback tracks any palette (light, dark, or dynamic) the [theme] resolves at draw time.
     */
    private fun blendTint(base: Int, over: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val inv = 1f - f
        val r = (android.graphics.Color.red(base) * inv + android.graphics.Color.red(over) * f).toInt()
        val g = (android.graphics.Color.green(base) * inv + android.graphics.Color.green(over) * f).toInt()
        val b = (android.graphics.Color.blue(base) * inv + android.graphics.Color.blue(over) * f).toInt()
        return android.graphics.Color.argb(0xFF, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

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
        handler.postDelayed(r, longPressDelayMs)
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

    // ── Space-cursor gesture lifecycle ────────────────────────────────────────

    private fun cancelSpaceCursorGesture() {
        cursorController.reset()
        spacePointerId = NO_ACTIVE_POINTER
    }

    // ── Preview lifecycle helpers ─────────────────────────────────────────────

    private fun dismissPreview(pointerId: Int) {
        previews[pointerId]?.dismiss()
    }

    // ── Pressed-key tint lifecycle ─────────────────────────────────────────────

    /**
     * Records [keyId] as the pressed key for [pointerId], releasing any key the pointer
     * previously held. Keeps [pressedKeys] (drawn) in lock-step with [pressedKeyByPointer].
     */
    private fun setPressedKey(pointerId: Int, keyId: KeyId) {
        val previous = pressedKeyByPointer.put(pointerId, keyId)
        if (previous != null && previous != keyId) {
            // Only un-tint the old key if no other finger is still on it.
            if (pressedKeyByPointer.values.none { it == previous }) pressedKeys -= previous
        }
        pressedKeys += keyId
    }

    /**
     * Drops the pressed tint owned by [pointerId]. Returns true if anything changed (so the
     * caller can decide whether to invalidate). The shared [pressedKeys] entry is removed only
     * when no other pointer is still resting on that same key.
     */
    private fun clearPressedKey(pointerId: Int): Boolean {
        val keyId = pressedKeyByPointer.remove(pointerId) ?: return false
        if (pressedKeyByPointer.values.none { it == keyId }) pressedKeys -= keyId
        return true
    }

    /** Clears every pressed tint (ACTION_CANCEL). */
    private fun clearAllPressedKeys() {
        pressedKeyByPointer.clear()
        pressedKeys.clear()
    }

    private fun dismissAllPreviews() {
        previews.values.forEach { it.dismiss() }
    }

    // ── Test seams ────────────────────────────────────────────────────────────

    /** Exposes the pool size for testing without exposing the pool itself. */
    internal fun activePointerCount(): Int = pool.size

    /** Number of keys currently drawn with the pressed tint. Exposed for test assertions only. */
    internal fun pressedKeyCount(): Int = pressedKeys.size

    /** Returns true if [keyId] is currently drawn pressed. Exposed for test assertions only. */
    internal fun isKeyPressed(keyId: KeyId): Boolean = keyId in pressedKeys

    /**
     * Returns true if a key-preview popup is currently visible for [pointerId].
     * Exposed for test assertions only.
     */
    internal fun isPreviewShowing(pointerId: Int): Boolean =
        previews[pointerId]?.isShowing == true

    /**
     * Returns the glyph currently rendered in the preview bubble for [pointerId], or null if no
     * bubble exists. Used by tests to assert masked fields show the redacted dot, not the key.
     */
    internal fun previewText(pointerId: Int): CharSequence? =
        previews[pointerId]?.displayedText

    /**
     * Returns true if a long-press alternate tray is currently visible for [pointerId].
     * Exposed for test assertions only.
     */
    internal fun isLongPressShowing(pointerId: Int): Boolean =
        longPressPopups[pointerId]?.isShowing == true

    /**
     * Returns true once the long-press timer has fired for [pointerId] — i.e. the configured
     * touch-and-hold delay elapsed and the tray was triggered — regardless of whether the
     * [LongPressPopup]'s window has finished mapping. Exposed for test assertions only.
     *
     * This is the state the touch-and-hold delay preference actually governs, so on-device
     * timing tests can assert the threshold without depending on PopupWindow window-token
     * timing (which is irrelevant to the delay and flaky to observe on the emulator).
     */
    internal fun isLongPressActive(pointerId: Int): Boolean =
        pointerId in longPressActive

    /**
     * Returns the currently highlighted alternate index in the long-press tray, or -1.
     * Exposed for test assertions only.
     */
    internal fun longPressSelectedIndex(pointerId: Int): Int =
        longPressPopups[pointerId]?.selectedIndex ?: -1

    /**
     * Returns true when the cursor-control gesture is currently active for the space key.
     * Exposed for test assertions only.
     */
    internal fun isCursorGestureActive(): Boolean = cursorController.gestureActive

    private companion object {
        const val TAG = "KeyPlaneView"

        // Fallback row count used when currentRows is empty (e.g. before the first layout sync).
        // Matches the standard QWERTY row count: letter rows + bottom action row.
        const val DEFAULT_ROW_COUNT = 4

        // Width (in dp) of the visual gap drawn between the two halves in SPLIT mode.
        // Wide enough to be a recognisable separator without wasting touch-target space.
        const val SPLIT_GAP_PX = 24f

        // The split-gap strip is drawn at this fraction of the keyboard background's RGB channels.
        // Values < 1.0 darken the gap relative to the keyboard background in light theme;
        // the same factor makes dark-theme backgrounds slightly lighter via the complementary path.
        const val SPLIT_GAP_DIM_FACTOR = 0.80f

        // Minimum leftward pixel travel required to trigger the swipe-left word-delete gesture
        // on the backspace key. 40 dp at 2× density = 80 px; the constant is in raw pixels so
        // the view's initialiser can scale it from its display metrics once, below.
        // The value here (in dp) is converted to px at construction via displayMetrics.density.
        const val SWIPE_LEFT_THRESHOLD_DP = 40f

        // Sentinel used for backspacePointerId when no backspace pointer is active.
        const val NO_ACTIVE_POINTER = -1

        // Fraction of the key-text colour blended into the key background for the pressed tint.
        // ~20% is enough to be clearly visible as a press without obscuring the key label.
        const val PRESSED_TINT_FRACTION = 0.20f

        // Corner hint glyph size relative to the main key-text size. ~0.55× matches the small
        // keycap hints on Samsung/Gboard — legible but clearly secondary to the main label.
        const val HINT_TEXT_SCALE = 0.55f

        // Alpha (0–255) of the hint glyph. ~40% keeps it faint so the eye reads the main label
        // first; the hint is a discoverability aid, not a primary character.
        const val HINT_ALPHA = 102
    }
}
