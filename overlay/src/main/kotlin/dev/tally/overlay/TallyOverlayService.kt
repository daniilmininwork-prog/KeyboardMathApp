package dev.tally.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
 *   4. When a suggestion arrives, the chip is shown above the focused field.
 *   5. User taps the chip → result is inserted via clipboard paste, clipboard restored.
 *   6. On window change or field blur the chip is dismissed.
 *
 * Privacy guarantees maintained:
 *   - Only [event.source] (the focused node) is read; the window tree is never traversed.
 *   - Text is never logged, stored, or transmitted.
 *   - No network permission exists, so exfiltration is impossible by construction.
 */
class TallyOverlayService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "TallyOverlayService"
        /** Log every failure up to this threshold; suppress subsequent ones to avoid flooding. */
        private const val MAX_CONSECUTIVE_EVENT_ERRORS = 5
    }

    private var chipWindow: OverlayChipWindow? = null
    private var evaluator: MathEvaluator? = null
    private var currentSuggestion: dev.tally.math.Suggestion? = null
    private var prefs: TallyPreferences? = null

    /** Counts consecutive handleTextEvent failures; reset to 0 on any success. */
    private var consecutiveEventErrors = 0

    override fun onServiceConnected() {
        prefs = TallyPreferences(this)

        val window = OverlayChipWindow(this).also { w ->
            w.onInsert = { insertCurrentSuggestion() }
        }
        chipWindow = window

        evaluator = MathEvaluator { suggestion ->
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = event.source ?: run {
                    clearSuggestion()
                    return
                }
                try {
                    // Guard against unexpected exceptions from handleTextEvent (e.g.
                    // StringIndexOutOfBoundsException in TextExtractor, or exceptions thrown
                    // while the evaluator is in a partially-torn-down state). Letting these
                    // propagate out of onAccessibilityEvent would crash the service process.
                    handleTextEvent(node)
                    consecutiveEventErrors = 0  // reset on success
                } catch (e: Exception) {
                    // Log and swallow to keep the service alive; the suggestion is not updated.
                    consecutiveEventErrors++
                    if (consecutiveEventErrors <= MAX_CONSECUTIVE_EVENT_ERRORS) {
                        Log.e(TAG, "handleTextEvent threw on event type ${event.eventType} " +
                            "(consecutive failures: $consecutiveEventErrors); ignoring event", e)
                    } else if (consecutiveEventErrors == MAX_CONSECUTIVE_EVENT_ERRORS + 1) {
                        Log.e(TAG, "handleTextEvent failure threshold reached " +
                            "($MAX_CONSECUTIVE_EVENT_ERRORS); suppressing further identical logs", e)
                    }
                    // After threshold: errors are counted but not logged to avoid flooding.
                } finally {
                    @Suppress("DEPRECATION") node.recycle()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> clearSuggestion()
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
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleTextEvent(node: AccessibilityNodeInfo) {
        if (!node.isEditable) {
            clearSuggestion()
            return
        }
        val p = prefs ?: return
        if (!p.enabled) {
            clearSuggestion()
            return
        }

        val text = node.text?.toString() ?: ""
        val cursorEnd = node.textSelectionEnd.coerceAtLeast(0)
        val excerpt = TextExtractor.extractBeforeCursor(text, cursorEnd)

        evaluator?.onTextChanged(
            text        = excerpt,
            percentMode = p.percentMode,
            precision   = p.precision,
        )
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
            @Suppress("DEPRECATION") node.recycle()
        }
    }

    private fun insertCurrentSuggestion() {
        val suggestion = currentSuggestion ?: return
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val inserter = ClipboardInserter(getSystemService(ClipboardManager::class.java), mainHandler)
            inserter.insert(node, suggestion.display)
        } catch (e: SecurityException) {
            // Clipboard access restricted (Android 10+ background clipboard policy).
            Log.e(TAG, "ClipboardInserter.insert failed: clipboard access denied (SecurityException); suggestion discarded", e)
            android.widget.Toast.makeText(this, "Could not insert result — clipboard access denied", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            // Accessibility node action failed, dead binder, or other runtime failure.
            // Log and swallow so the service survives; clearSuggestion() in finally dismisses
            // the stale chip. Narrow to RuntimeException so programming errors (e.g.
            // NullPointerException in ClipboardInserter) are surfaced rather than swallowed.
            Log.e(TAG, "ClipboardInserter.insert failed: ${e.javaClass.simpleName}; suggestion discarded", e)
        } finally {
            @Suppress("DEPRECATION") node.recycle()
            // Always clear the suggestion so the chip is dismissed and the stale node
            // reference is released, even if insert() threw.
            clearSuggestion()
        }
    }

    private fun clearSuggestion() {
        currentSuggestion = null
        evaluator?.cancel()
        chipWindow?.dismiss()
    }
}
