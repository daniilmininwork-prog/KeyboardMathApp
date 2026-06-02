package dev.tally.overlay

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.lang.reflect.Method

/**
 * Determines whether a [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED] event represents committed
 * text or in-progress composition, and whether evaluation should proceed.
 *
 * ## Background
 *
 * On Android 17 (API 37), the platform added [AccessibilityEvent.getTextChangeTypes] and the
 * [AccessibilityEvent.TEXT_DATA_TYPE_COMMITTED] flag specifically to let services distinguish
 * in-flight composing updates from actual commits. Before API 37, services cannot distinguish
 * composing updates from committed text via any public API.
 *
 * Since the overlay compiles against SDK 36, the API 37 methods are accessed via reflection.
 * The method is looked up once and cached; if lookup fails for any reason the filter degrades
 * gracefully to "pass all events" — the same behaviour as pre-API-37 builds.
 *
 * On pre-API-37 devices:
 *   - [TYPE_VIEW_TEXT_SELECTION_CHANGED] is a stronger commit signal (selection moves after commit)
 *     and is always passed through.
 *   - [TYPE_VIEW_TEXT_CHANGED] events are always passed through; the debounce in [MathEvaluator]
 *     absorbs the composing churn. Reads after a debounce window typically catch committed state.
 *   - The user-visible consequence is documented (AC-7): on composing-heavy keyboards the chip
 *     appears reliably only *after* the user commits (space / punctuation / suggestion tap).
 *
 * On API 37+ devices:
 *   - [TYPE_VIEW_TEXT_CHANGED] events where [getTextChangeTypes] returns a composition-only
 *     mask (no [TEXT_DATA_TYPE_COMMITTED] bit) are **skipped**. Evaluation only runs when the
 *     event carries a commit (or when the type is ambiguous/unknown).
 *   - [TYPE_VIEW_TEXT_SELECTION_CHANGED] is always passed through — it is generated on commit
 *     and is not subject to composition filtering.
 */
internal object CommitFilter {

    /**
     * Reflected [AccessibilityEvent.getTextChangeTypes]. Non-null only on API 37+.
     *
     * Lookup is attempted once. Any reflection error (class not found, method not found,
     * security restriction) leaves this null, which degrades the filter to pass-all.
     */
    private val getTextChangeTypes: Method? by lazy {
        if (Build.VERSION.SDK_INT < 37) {
            null
        } else {
            try {
                AccessibilityEvent::class.java.getMethod("getTextChangeTypes")
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    /**
     * Reflected constant [AccessibilityEvent.TEXT_DATA_TYPE_COMMITTED].
     *
     * Value is 1 on API 37+; -1 signals "unknown / reflection failed."
     */
    private val TEXT_DATA_TYPE_COMMITTED: Int by lazy {
        if (Build.VERSION.SDK_INT < 37) {
            -1
        } else {
            try {
                AccessibilityEvent::class.java.getField("TEXT_DATA_TYPE_COMMITTED").getInt(null)
            } catch (_: Exception) {
                -1
            }
        }
    }

    /**
     * Returns `true` when [event] should trigger a math evaluation, `false` when it should
     * be skipped because it carries only in-progress composition (no commit).
     *
     * Selection-change events are always considered commit signals — a selection-position
     * update follows text commit on virtually every keyboard, and selection changes do not
     * carry composition state.
     *
     * Text-change events on API 37+ are inspected: if the change-type mask has no
     * [TEXT_DATA_TYPE_COMMITTED] bit, the event is composition-only and skipped.
     * On older API levels all text-change events pass through.
     */
    fun shouldEvaluate(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            // Selection changes reliably follow text commits and do not carry composition state.
            return true
        }

        // For text-change events on pre-37 devices there is no commit/compose distinction;
        // pass all events through and let the debounce absorb composing churn.
        val method = getTextChangeTypes ?: return true
        val committedBit = TEXT_DATA_TYPE_COMMITTED
        if (committedBit < 0) return true

        return try {
            val changeTypes = method.invoke(event) as? Int ?: return true
            // changeTypes == 0 means the field is empty (selection, commit implied).
            // Any non-zero mask without the committed bit is pure composition — skip it.
            changeTypes == 0 || (changeTypes and committedBit) != 0
        } catch (_: Exception) {
            // Reflection call failed; degrade gracefully to pass-all.
            true
        }
    }
}
