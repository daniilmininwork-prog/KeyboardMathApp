package dev.tally.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.tally.glue.MathEvaluator
import dev.tally.glue.TallyPreferences

/**
 * Accessibility service that powers the optional overlay mode.
 *
 * Lifecycle:
 *   1. User enables the service in Accessibility Settings.
 *   2. [onServiceConnected] fires; chip window and evaluator are created.
 *   3. On each text/focus/selection event the focused editable node's text is extracted
 *      and fed to [MathEvaluator]. The evaluator debounces and evaluates off the UI thread.
 *   4. When a suggestion arrives (main thread), the generation and focus-token are checked;
 *      a stale result is silently dropped; a current result shows the chip.
 *   5. User taps the chip → result is inserted via accessibility actions (no clipboard).
 *   6. Only genuine focus-loss clears the chip — window churn is ignored.
 *
 * Clipboard is never touched anywhere in this class. Insertion is handled by
 * [OverlayInserter] using [AccessibilityNodeInfo.ACTION_SET_TEXT].
 *
 * Privacy guarantees:
 *   - Only the focused editable node is read; the window tree is never traversed for content.
 *   - Text is never logged, stored, or transmitted.
 *   - No network permission exists, so exfiltration is impossible by construction.
 */
class TallyOverlayService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "TallyOverlayService"
        private const val MAX_CONSECUTIVE_EVENT_ERRORS = 5
    }

    private var chipWindow: OverlayChipWindow? = null
    private var evaluator: MathEvaluator? = null
    private var currentSuggestion: dev.tally.math.Suggestion? = null
    private var prefs: TallyPreferences? = null

    /**
     * Monotonically increasing request id. A result is applied only when its id matches
     * this value at delivery time — any evaluation started before a clear or focus change
     * is silently discarded.
     */
    @get:JvmSynthetic
    internal var generation: Int = 0
        private set

    /**
     * Identity of the editable field that triggered the current evaluation. Re-checked at
     * result delivery time; a mismatch means focus moved to a different field while the
     * evaluator was running.
     */
    @get:JvmSynthetic
    internal var focusToken: FocusToken? = null
        private set

    private var consecutiveEventErrors = 0

    override fun onServiceConnected() {
        prefs = TallyPreferences(this)

        val window = OverlayChipWindow(this).also { w ->
            w.onInsert = { insertCurrentSuggestion() }
        }
        chipWindow = window

        evaluator = MathEvaluator { suggestion ->
            // This lambda runs on the main thread. Check the generation and focus token so
            // a result from a superseded request (user moved focus, or clearSuggestion fired
            // while the evaluator was off-thread) is dropped rather than shown.
            val reqIdAtDelivery = pendingReqId
            val tokenAtDelivery = pendingToken
            if (reqIdAtDelivery != generation || tokenAtDelivery != focusToken) {
                return@MathEvaluator
            }
            currentSuggestion = suggestion
            if (suggestion != null) {
                val bounds = getFocusedFieldBounds()
                if (bounds != null) {
                    window.show(suggestion.display, bounds)
                } else {
                    window.dismiss()
                    currentSuggestion = null
                }
            } else {
                window.dismiss()
            }
        }
    }

    // These are set just before the evaluator is invoked and read inside the callback.
    // Both are main-thread fields; MathEvaluator delivers results on the main thread.
    private var pendingReqId: Int = 0
    private var pendingToken: FocusToken? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = event.source ?: run {
                    // Null source on a focus event means focus moved away from editable content.
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                        clearSuggestion()
                    }
                    return
                }
                try {
                    handleTextEvent(node, event)
                    consecutiveEventErrors = 0
                } catch (e: Exception) {
                    consecutiveEventErrors++
                    if (consecutiveEventErrors <= MAX_CONSECUTIVE_EVENT_ERRORS) {
                        Log.e(TAG, "handleTextEvent threw on event ${event.eventType} " +
                            "(consecutive: $consecutiveEventErrors)", e)
                    } else if (consecutiveEventErrors == MAX_CONSECUTIVE_EVENT_ERRORS + 1) {
                        Log.e(TAG, "handleTextEvent failure threshold reached; suppressing further logs", e)
                    }
                } finally {
                    recycleNode(node)
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // typeWindowsChanged fires on every window-list change (IME open/close, popups,
                // autocorrect bubbles). Do NOT clear the suggestion here — that caused the
                // "chip flashes and vanishes on window churn" bug (F2). Genuine blur is signaled
                // by a subsequent TYPE_VIEW_FOCUSED event with a null or non-editable source.
            }
        }
    }

    override fun onInterrupt() = clearSuggestion()

    override fun onDestroy() {
        super.onDestroy()
        evaluator?.cancel()
        chipWindow?.destroy()
        evaluator = null
        chipWindow = null
        prefs = null
        focusToken = null
        pendingToken = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleTextEvent(node: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val eventType = event.eventType
        if (!node.isEditable) {
            // Input focus moved to a non-editable node — genuine blur.
            if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                clearSuggestion()
            }
            return
        }

        val token = FocusToken.from(node)

        // A focus event on a different editable node means the user moved focus. Clear
        // any pending suggestion before starting evaluation for the new field.
        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && focusToken != null && token != focusToken) {
            clearSuggestion()
        }
        focusToken = token

        val p = prefs ?: return
        if (!p.enabled) {
            clearSuggestion()
            return
        }

        // Dormancy (AC-6): when Tally's own IME is the active keyboard the overlay is redundant
        // and must produce no chips — the InputConnection path handles everything.
        if (isTallyImeActive()) {
            clearSuggestion()
            return
        }

        // Secure-field hard gate (AC-2, 06 §3.2): refresh first so inputType/isPassword reflect
        // the live field, then bail BEFORE reading node.text. No read, no chip in sensitive fields.
        node.refresh()
        if (OverlaySecureField.isSecure(node.inputType, node.isPassword)) {
            clearSuggestion()
            return
        }

        // On text-change events, check whether this is a commit or composition-only update.
        // Composition updates (composing region growing/shifting) do not represent committed
        // text, so the chip should not evaluate until the IME commits. Selection-change
        // events are always a reliable commit signal and bypass this check.
        // On pre-API-37 devices the filter degrades to pass-all (see CommitFilter KDoc).
        if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            !CommitFilter.shouldEvaluate(event)) {
            return
        }

        // node.refresh() was already called above for the secure-field gate, so the snapshot is
        // current here — no second refresh needed before reading the text.
        val text = node.text?.toString() ?: ""
        val rawCursor = node.textSelectionEnd
        // -1 means selection end not reported (common on Samsung / some OEM fields).
        // Append-at-end is the safe fallback so expression search still runs.
        val cursorEnd = if (rawCursor < 0) text.length else rawCursor
        val excerpt = TextExtractor.extractBeforeCursor(text, cursorEnd)

        // Stamp the request before handing off to the evaluator. The onResult callback
        // checks these stamps to discard superseded results.
        generation++
        pendingReqId = generation
        pendingToken = token

        evaluator?.onTextChanged(
            text        = excerpt,
            percentMode = p.percentMode,
            precision   = p.precision,
        )
    }

    /**
     * True when Tally's own IME is the system's active keyboard, in which case the overlay must
     * stay dormant (AC-6). Reads [Settings.Secure.DEFAULT_INPUT_METHOD]; a read failure fails
     * open (overlay active) since the IME path being unavailable is the case the overlay assists.
     */
    private fun isTallyImeActive(): Boolean {
        val active = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read DEFAULT_INPUT_METHOD; assuming a third-party IME", e)
            null
        }
        return OverlayDormancy.isTallyImeActive(active)
    }

    private fun getFocusedFieldBounds(): Rect? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) null else rect
        } catch (e: RuntimeException) {
            Log.e(TAG, "getBoundsInScreen threw unexpectedly; treating as no bounds", e)
            null
        } finally {
            recycleNode(node)
        }
    }

    private fun insertCurrentSuggestion() {
        val suggestion = currentSuggestion ?: return
        val token = focusToken ?: return
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val result = try {
            // Re-validate that focus is still on the same field that produced the suggestion.
            if (FocusToken.from(node) != token) {
                Log.w(TAG, "insertCurrentSuggestion: focus moved since chip was shown; discarding")
                clearSuggestion()
                return
            }
            // Re-check the secure gate at insert time (AC-2): the field may have become a password
            // field, or Tally's IME may have taken over, between showing the chip and the tap.
            node.refresh()
            if (isTallyImeActive() || OverlaySecureField.isSecure(node.inputType, node.isPassword)) {
                Log.w(TAG, "insertCurrentSuggestion: field is now secure or Tally IME is active; not inserting")
                clearSuggestion()
                return
            }
            OverlayInserter.insert(node, suggestion.display)
        } finally {
            recycleNode(node)
        }

        // Tier 3 (FAILED): the editor rejected the write or read-back didn't match. Per §4.8 we
        // keep the chip on screen so its value stays visible for manual copy — clearing it would
        // hide the result the user asked for. The chip is dropped naturally on the next blur.
        // Tiers 1 & 2 succeeded, so the chip has done its job and is dismissed.
        if (result != OverlayInserter.InsertResult.FAILED) {
            clearSuggestion()
        } else {
            Log.d(TAG, "insertCurrentSuggestion: insertion not supported by this editor; chip left visible")
        }
    }

    private fun clearSuggestion() {
        generation++
        focusToken = null
        pendingToken = null
        currentSuggestion = null
        evaluator?.cancel()
        chipWindow?.dismiss()
    }

    /**
     * Releases [node] on API < 33. On API 33+ [AccessibilityNodeInfo.recycle] is a deprecated
     * no-op and calling it risks use-after-recycle if the framework ever reactivates pooling.
     */
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}
