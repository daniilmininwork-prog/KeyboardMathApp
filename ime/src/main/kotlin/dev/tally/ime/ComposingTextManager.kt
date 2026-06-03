package dev.tally.ime

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Manages the composing-text pipeline for a keyboard session.
 *
 * Responsibilities (03 §3.3):
 *
 *   1. **Composing region** — accumulates keystrokes via [setComposingText] so the editor shows
 *      the in-progress word with an underline. [finishComposing] commits and clears the region.
 *
 *   2. **Batch edits** — every compound operation (character with composing-region shift,
 *      autocorrect replace, double-space→period) is wrapped in [beginBatchEdit]/[endBatchEdit].
 *      Wraps are balanced; nesting is transparent to callers. The [InputConnectionMirror]'s
 *      [isMidBatchEdit] flag is kept in sync so [onUpdateSelection] re-entrancy is suppressed.
 *
 *   3. **Backspace via code-points** — [deleteCodePointsBefore] uses
 *      [InputConnection.deleteSurroundingTextInCodePoints] (surrogate/emoji-safe) and reflects
 *      the deletion in the [InputConnectionMirror] optimistically.
 *
 *   4. **Commit-only fallback** — the composing path requires editor cooperation. When composing
 *      is not appropriate (e.g. numeric field, TYPE_NULL editor) [commitText] is available as a
 *      direct commit that bypasses the composing region.
 *
 * All methods must be called on the main thread.
 */
internal class ComposingTextManager(
    private val mirror: InputConnectionMirror,
) {

    /** The text currently in the composing region, not yet committed. */
    private val composingBuffer = StringBuilder()

    /** True when a batch edit opened by this manager is currently active. */
    private var batchDepth = 0

    // ── Composing-text API ────────────────────────────────────────────────────

    /**
     * Append [ch] to the composing region.
     *
     * The editor shows the composing text with an underline and keeps the cursor at the end.
     * On the next [commitCurrentWord] (space/enter/punctuation) the composing region is
     * committed to the editor buffer.
     *
     * The call is wrapped in a batch edit so the editor treats the composing-region update
     * as an atomic unit and does not fire intermediate layout passes.
     *
     * @param ic Active [InputConnection]; no-op when null.
     */
    fun appendToComposing(ch: Char, ic: InputConnection?) {
        if (ic == null) return
        composingBuffer.append(ch)
        withBatchEdit(ic) {
            if (!ic.setComposingText(composingBuffer.toString(), 1)) {
                Log.w(TAG, "setComposingText returned false — IC may be invalidated")
            }
        }
        // The mirror reflects the composing text as part of textBefore for evaluation purposes.
        mirror.appendCommitted(ch.toString())
    }

    /**
     * Commit the current composing region to the editor and clear it.
     *
     * After this call the editor no longer shows an underline for the just-typed word.
     * The [InputConnectionMirror] is not updated here because [onUpdateSelection] will fire and
     * [reconcile] will refresh it — except when called mid-batch-edit, where the mirror was
     * already kept up-to-date by [appendToComposing].
     *
     * @param ic Active [InputConnection]; no-op when null.
     */
    fun finishComposing(ic: InputConnection?) {
        if (ic == null) return
        withBatchEdit(ic) {
            if (!ic.finishComposingText()) {
                Log.w(TAG, "finishComposingText returned false — IC may be invalidated")
            }
        }
        composingBuffer.clear()
    }

    /**
     * Replace the current composing region with [replacement] and commit it.
     *
     * Used by autocorrect-on-space: the in-progress word is swapped for the corrected
     * candidate, then the caller appends the space via [commitText]. The whole sequence runs
     * inside one batch edit so the editor sees an atomic replace-then-space with clean undo.
     *
     * The [InputConnectionMirror] tracked the typed word optimistically (one [appendCommitted]
     * per keystroke), so the old word is rolled back from the mirror by code-point count before
     * the replacement is appended — otherwise the mirror would read "tehthe" and corrupt the
     * next prediction/math query.
     *
     * No-op when there is no composing region or [ic] is null; callers fall back to a plain
     * commit in that case.
     *
     * @param ic Active [InputConnection]; no-op when null.
     */
    fun replaceComposingWith(replacement: CharSequence, ic: InputConnection?) {
        if (ic == null || composingBuffer.isEmpty()) return
        val oldCodePoints = composingBuffer.codePointCount(0, composingBuffer.length)
        withBatchEdit(ic) {
            if (!ic.setComposingText(replacement, 1)) {
                Log.w(TAG, "replaceComposingWith/setComposingText returned false — IC may be invalidated")
            }
            if (!ic.finishComposingText()) {
                Log.w(TAG, "replaceComposingWith/finishComposingText returned false — IC may be invalidated")
            }
        }
        composingBuffer.clear()
        // Roll the optimistic per-keystroke mirror appends back, then reflect the replacement.
        mirror.deleteBeforeCodePoints(oldCodePoints)
        mirror.appendCommitted(replacement)
    }

    /**
     * Commit [text] directly without going through the composing region.
     *
     * Use this for space, punctuation that terminates a word (commits and then appends the
     * punctuation), and special characters that should never show an underline.
     *
     * Any open composing region is finished first so the commit lands after the composed word.
     *
     * @param ic Active [InputConnection]; no-op when null.
     */
    fun commitText(text: CharSequence, ic: InputConnection?) {
        if (ic == null) return
        withBatchEdit(ic) {
            if (composingBuffer.isNotEmpty()) {
                if (!ic.finishComposingText()) {
                    // Log at error level: if finishComposingText fails the composing text may not
                    // have been committed to the editor, meaning the editor and local buffer will
                    // diverge. This is a data-integrity failure, not a benign IC hint.
                    // We still clear the buffer below so the IME side is consistent; the
                    // editor may be missing the composed text, but leaving the buffer dirty would
                    // cause subsequent keystrokes to operate on phantom composing state.
                    Log.e(TAG, "commitText/finishComposingText returned false — editor buffer may not match local state; data-integrity risk")
                }
                composingBuffer.clear()
            }
            if (!ic.commitText(text, 1)) {
                Log.w(TAG, "commitText returned false — IC may be invalidated")
            }
        }
        mirror.appendCommitted(text)
    }

    /**
     * Delete the word immediately before the cursor in a single batch edit.
     *
     * "Word" is defined as the longest run of non-whitespace code points immediately
     * preceding the cursor. Leading whitespace between the cursor and the word is consumed
     * first, matching the behaviour of Gboard and AOSP LatinIME swipe-left-on-delete.
     *
     * If the composing region is non-empty it is committed before the word deletion so the
     * editor's buffer is consistent before we query it.
     *
     * The deletion is reflected optimistically in the [InputConnectionMirror] so subsequent
     * keystrokes see the correct context before [onUpdateSelection] fires.
     *
     * @param ic Active [InputConnection]; no-op when null.
     */
    fun deleteWordBefore(ic: InputConnection?) {
        if (ic == null) return

        // Finish any in-progress composing region first so the mirror and IC are aligned.
        if (composingBuffer.isNotEmpty()) {
            finishComposing(ic)
        }

        val before = mirror.textBefore.toString()
        if (before.isEmpty()) return

        // Walk backwards through code points: skip trailing whitespace, then consume the word.
        var idx = before.length
        var deleteCodePoints = 0

        // Skip any trailing whitespace.
        while (idx > 0) {
            val cp = Character.codePointBefore(before, idx)
            if (!Character.isWhitespace(cp)) break
            idx -= Character.charCount(cp)
            deleteCodePoints++
        }

        // Consume the non-whitespace run (the "word").
        while (idx > 0) {
            val cp = Character.codePointBefore(before, idx)
            if (Character.isWhitespace(cp)) break
            idx -= Character.charCount(cp)
            deleteCodePoints++
        }

        if (deleteCodePoints == 0) return

        val toDelete = deleteCodePoints
        withBatchEdit(ic) {
            if (!ic.deleteSurroundingTextInCodePoints(toDelete, 0)) {
                Log.w(TAG, "deleteWordBefore: deleteSurroundingTextInCodePoints($toDelete,0) returned false")
            }
        }
        mirror.deleteBeforeCodePoints(toDelete)
    }

    /**
     * Delete [count] Unicode code points before the cursor.
     *
     * Uses [InputConnection.deleteSurroundingTextInCodePoints] which is surrogate-pair-aware and
     * emoji-safe — unlike [InputConnection.deleteSurroundingText] which counts [Char] units and
     * can split a surrogate pair or leave a dangling modifier behind.
     *
     * If the composing region is non-empty, the backspace first trims the composing buffer; only
     * when the buffer is empty does deletion reach committed text.
     *
     * @param count     Number of code points to delete; must be ≥ 1.
     * @param ic        Active [InputConnection]; no-op when null.
     */
    fun deleteCodePointsBefore(count: Int, ic: InputConnection?) {
        if (ic == null || count <= 0) return

        var remaining = count

        // Trim the composing buffer before touching committed text.
        if (composingBuffer.isNotEmpty()) {
            val bufferLen = composingBuffer.codePointCount(0, composingBuffer.length)
            val fromBuffer = minOf(remaining, bufferLen)
            // Remove trailing code points from the composing buffer.
            var charIdx = composingBuffer.length
            var removed = 0
            while (removed < fromBuffer && charIdx > 0) {
                val cp = Character.codePointBefore(composingBuffer, charIdx)
                charIdx -= Character.charCount(cp)
                removed++
            }
            composingBuffer.delete(charIdx, composingBuffer.length)
            remaining -= fromBuffer

            withBatchEdit(ic) {
                if (composingBuffer.isEmpty()) {
                    if (!ic.finishComposingText()) {
                        Log.w(TAG, "deleteCodePointsBefore/finishComposingText returned false")
                    }
                } else {
                    if (!ic.setComposingText(composingBuffer.toString(), 1)) {
                        Log.w(TAG, "deleteCodePointsBefore/setComposingText returned false")
                    }
                }
                if (remaining > 0) {
                    if (!ic.deleteSurroundingTextInCodePoints(remaining, 0)) {
                        Log.w(TAG, "deleteCodePointsBefore: deleteSurroundingTextInCodePoints($remaining,0) returned false")
                    }
                }
            }
        } else {
            var deleteSucceeded = false
            withBatchEdit(ic) {
                deleteSucceeded = ic.deleteSurroundingTextInCodePoints(remaining, 0)
                if (!deleteSucceeded) {
                    Log.w(TAG, "deleteCodePointsBefore: deleteSurroundingTextInCodePoints($remaining,0) returned false; " +
                        "skipping mirror update to avoid mirror/editor divergence")
                }
            }
            // Only update the mirror if the editor confirmed the deletion. Updating optimistically
            // when the delete failed would cause the mirror to diverge from the true editor state,
            // producing wrong math candidates until the next onUpdateSelection reconcile.
            if (deleteSucceeded) {
                mirror.deleteBeforeCodePoints(count)
            }
            return
        }

        mirror.deleteBeforeCodePoints(count)
    }

    // ── Batch-edit helpers ────────────────────────────────────────────────────

    /**
     * Execute [block] wrapped in a single batch-edit frame.
     *
     * Nested calls increment the depth counter; [InputConnection.beginBatchEdit] and
     * [endBatchEdit] are called only at depth transitions 0→1 and 1→0, keeping the pair
     * balanced even when operations compose. The [InputConnectionMirror.isMidBatchEdit] flag
     * is kept in sync so [onUpdateSelection] calls that arrive mid-batch are ignored.
     */
    private inline fun withBatchEdit(ic: InputConnection, block: () -> Unit) {
        if (batchDepth == 0) {
            ic.beginBatchEdit()
            mirror.onBatchEditBegin()
        }
        batchDepth++
        try {
            block()
        } finally {
            batchDepth--
            if (batchDepth == 0) {
                ic.endBatchEdit()
                mirror.onBatchEditEnd()
            }
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "ComposingTextManager"
    }

    /** The current composing-region text; empty when no word is in flight. */
    val composingText: String get() = composingBuffer.toString()

    /** True when there is an in-progress composing region. */
    val isComposing: Boolean get() = composingBuffer.isNotEmpty()

    /**
     * Clear composing state without touching the [InputConnection].
     *
     * Called from [TallyInputMethodService.onFinishInputView] (via [KeyboardController.teardown])
     * where the IC may already be gone. Clears the composing buffer, resets [batchDepth] to zero,
     * and — only when we were actually mid-batch-edit — resets the mirror flag so that
     * [InputConnectionMirror.isMidBatchEdit] is false after teardown without corrupting a
     * legitimately-running batch edit started by a caller above us.
     */
    fun resetWithoutIc() {
        val wasMidBatch = batchDepth > 0
        composingBuffer.clear()
        batchDepth = 0
        if (wasMidBatch) {
            // Only call onBatchEditEnd when we opened a batch that was never closed, so we
            // do not toggle the mirror flag from false → false (no-op) or, worse, flip it
            // to false when a legitimate outer batch edit is still in progress.
            mirror.onBatchEditEnd()
        }
    }
}
