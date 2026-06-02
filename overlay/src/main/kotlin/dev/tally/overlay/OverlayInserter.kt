package dev.tally.overlay

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts a math result into a focused editable node without ever touching the clipboard.
 *
 * The insertion follows the tiered fallback ladder from `02-overlay-root-cause-and-redesign.md`
 * §4.8. There is no clipboard tier: the removed `setPrimaryClip` + `ACTION_PASTE` path silently
 * destroyed the user's clipboard on Android 10+ and toasted on Android 12+ (AC-1).
 *
 * | Tier | Mechanism | Outcome |
 * |---|---|---|
 * | 1 | `ACTION_SET_TEXT`(full reconstructed) + `ACTION_SET_SELECTION`, read-back verified | [InsertResult.INSERTED] |
 * | 2 | `ACTION_SET_TEXT` verified, but selection-restore rejected → accept caret-at-end | [InsertResult.INSERTED_AT_END] |
 * | 3 | `ACTION_SET_TEXT` rejected, or read-back mismatch → no-op, field untouched | [InsertResult.FAILED] |
 *
 * Tier 3 honours the correct-or-silent rule (AC-3): on any sign the write did not land cleanly,
 * the field is left exactly as the user had it and the caller keeps the chip visible so the
 * value can still be copied manually. The overlay never auto-inserts and never auto-copies.
 */
internal object OverlayInserter {

    private const val TAG = "OverlayInserter"

    enum class InsertResult {
        /** Tier 1: text written, read-back verified, caret restored after the inserted span. */
        INSERTED,

        /** Tier 2: text written and verified, but the editor rejected selection restore; caret left at end. */
        INSERTED_AT_END,

        /** Tier 3: editor rejected `ACTION_SET_TEXT` or read-back did not match; field left unchanged. */
        FAILED,
    }

    fun insert(node: AccessibilityNodeInfo, result: String): InsertResult {
        node.refresh()

        val full = node.text?.toString() ?: ""
        val (selStart, selEnd) = normalizeSelection(
            node.textSelectionStart,
            node.textSelectionEnd,
            full.length,
        )

        // Collapse any composing region before mutating: a SET_SELECTION to a collapsed caret
        // reduces Samsung's "restore composing region" duplication path (flutter#31512). This is
        // a best-effort mitigation — its failure does not by itself drop us to a lower tier.
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, collapsedSelectionArgs(selEnd))

        val newText = splice(full, selStart, selEnd, result)
        val setTextOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            },
        )
        if (!setTextOk) {
            Log.d(TAG, "ACTION_SET_TEXT not supported; insertion skipped (tier 3)")
            return InsertResult.FAILED
        }

        // Read-back verify (AC-3): trust the field's value, not the keyboard's view. A mismatch
        // means the IME mutated concurrently (Samsung getExtractedText misuse / composing-region
        // duplication) — bail with no claim of success rather than risk corruption.
        node.refresh()
        val readBackMatches = (node.text?.toString() ?: "") == newText
        if (!readBackMatches) {
            Log.w(TAG, "read-back mismatch after ACTION_SET_TEXT; leaving field as-is (tier 3)")
            return InsertResult.FAILED
        }

        val caretPos = selStart + result.length
        val caretRestored = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            collapsedSelectionArgs(caretPos),
        )

        return resolveResult(setTextOk = true, readBackMatches = true, caretRestored = caretRestored)
    }

    /**
     * Resolves the [InsertResult] tier from the three observable outcomes of an insertion
     * attempt. Extracted as a pure function so the ladder is unit-testable without a live
     * [AccessibilityNodeInfo].
     */
    internal fun resolveResult(
        setTextOk: Boolean,
        readBackMatches: Boolean,
        caretRestored: Boolean,
    ): InsertResult = when {
        !setTextOk -> InsertResult.FAILED
        !readBackMatches -> InsertResult.FAILED
        caretRestored -> InsertResult.INSERTED
        else -> InsertResult.INSERTED_AT_END
    }

    /**
     * Normalises a reported selection to a valid `(start, end)` pair within `[0, length]`.
     *
     * Samsung and some OEM fields report `-1` for [AccessibilityNodeInfo.textSelectionEnd] when
     * no caret position is published (notably right after the keyboard is hidden and reshown).
     * In that case insertion appends at the end of the field rather than dropping the result.
     */
    internal fun normalizeSelection(rawStart: Int, rawEnd: Int, length: Int): Pair<Int, Int> {
        val end = if (rawEnd < 0) length else rawEnd.coerceIn(0, length)
        val start = if (rawStart < 0) end else rawStart.coerceIn(0, length)
        return start to end
    }

    /**
     * Returns a new string with the range [[selStart], [selEnd]) replaced by [replacement].
     * Handles reversed or out-of-range cursors gracefully by clamping to valid bounds.
     */
    internal fun splice(full: String, selStart: Int, selEnd: Int, replacement: String): String {
        val start = selStart.coerceIn(0, full.length)
        val end = selEnd.coerceIn(start, full.length)
        return full.substring(0, start) + replacement + full.substring(end)
    }

    private fun collapsedSelectionArgs(position: Int): Bundle = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
    }
}
