package dev.tally.ime

import android.os.Build
import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * A locally-maintained mirror of the editing context visible through the active [InputConnection].
 *
 * Every [InputConnection] read is an IPC round-trip and is racy (03 §3.3). Reading per-keystroke
 * is the primary cause of jank and ANRs in IME implementations. This mirror breaks that coupling:
 *
 *   - Populated once at field entry from [InputConnection.getTextBeforeCursor] /
 *     [InputConnection.getTextAfterCursor] (or [InputConnection.getSurroundingText] on API 31+).
 *   - Kept current by [reconcile] whenever [onUpdateSelection] fires or the IME commits text.
 *   - Read from any code that needs editing context — zero IPC.
 *
 * The mirror's view of the buffer may lag the editor by one batch-edit cycle in the face of
 * programmatic text changes (paste, autofill, etc.). [reconcile] is the hook that closes that
 * gap on the next [onUpdateSelection] notification.
 *
 * All mutation and reads must happen on the main thread.
 */
internal class InputConnectionMirror {

    /** Text before the cursor (up to [MAX_MIRROR_LENGTH] characters). */
    var textBefore: CharSequence = ""
        private set

    /** Text after the cursor (up to [MAX_MIRROR_LENGTH] characters). */
    var textAfter: CharSequence = ""
        private set

    /** Current selection start, or -1 when unknown. */
    var selStart: Int = -1
        private set

    /** Current selection end (== [selStart] when collapsed), or -1 when unknown. */
    var selEnd: Int = -1
        private set

    /**
     * True while the IME is executing its own batch edit. [onUpdateSelection] calls that arrive
     * during our own edits should be ignored to prevent re-entrant evaluation.
     */
    var isMidBatchEdit: Boolean = false
        private set

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Seed the mirror from a live [InputConnection] at field-entry time.
     *
     * On API 31+ this uses [InputConnection.getSurroundingText] to get both sides of the cursor
     * in a single IPC call. Below 31 we make two separate calls.
     *
     * [ic] may be null during tests or for transient null-IC conditions; in that case the mirror
     * is reset to an empty-unknown state.
     *
     * @param editorPackageName Optional package name from [EditorInfo.packageName]; included in
     *   the non-compliance warning log so OEM bugs can be reported with enough context to identify
     *   the offending editor.
     */
    fun seed(ic: InputConnection?, editorPackageName: String? = null) {
        if (ic == null) {
            reset()
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = ic.getSurroundingText(MAX_MIRROR_LENGTH, MAX_MIRROR_LENGTH, 0)
            if (surrounding != null) {
                textBefore = surrounding.text.subSequence(0, surrounding.selectionStart)
                textAfter  = surrounding.text.subSequence(surrounding.selectionEnd, surrounding.text.length)
                selStart   = surrounding.selectionStart
                selEnd     = surrounding.selectionEnd
                return
            }
            // getSurroundingText returned null on API 31+. Some OEM/custom EditText
            // implementations do not override the method despite declaring API 31 compliance.
            // Fall through to the two-call path so we still get text, but log the editor
            // package and editorInfo so non-compliant editors can be identified and reported.
            // selStart/selEnd will be -1 after the fallback; downstream code must guard.
            Log.w(TAG, "getSurroundingText returned null on API ${Build.VERSION.SDK_INT}; " +
                "using two-call fallback (non-compliant editor) — " +
                "editor package: ${editorPackageName ?: "unknown"}; " +
                "selStart/selEnd will be -1 until next reconcile")
        }
        // Fallback (API < 31 or getSurroundingText unavailable/non-compliant).
        // selStart/selEnd are unknown in this path (-1) — any selection-position-dependent
        // logic must guard against -1.
        textBefore = ic.getTextBeforeCursor(MAX_MIRROR_LENGTH, 0) ?: ""
        textAfter  = ic.getTextAfterCursor(MAX_MIRROR_LENGTH, 0) ?: ""
        selStart   = -1
        selEnd     = -1
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    /**
     * Reconcile the mirror after [onUpdateSelection] fires.
     *
     * When the selection move is caused by a third party (paste, autofill, direct editor
     * manipulation) the mirror's cached [textBefore]/[textAfter] may be stale. Re-reading from
     * [ic] here keeps the mirror accurate without reading on every keystroke.
     *
     * Re-entrancy: if [isMidBatchEdit] is true this notification was triggered by our own
     * commit; skip the read to avoid a redundant IPC and, more importantly, to prevent recursive
     * evaluation that could corrupt composing state.
     *
     * @param newSelStart Cursor start reported by the platform.
     * @param newSelEnd   Cursor end reported by the platform.
     * @param ic          The active [InputConnection]; may be null if the field went away.
     * @return true if the caller should re-trigger content evaluation (the change was external).
     */
    fun reconcile(newSelStart: Int, newSelEnd: Int, ic: InputConnection?): Boolean {
        if (isMidBatchEdit) return false   // our own edit caused this; mirror is already fresh

        if (ic == null) {
            // The field was just dismissed or the binder died. Reset the mirror so it does not
            // hold stale text, but return false — there is no live field to evaluate against.
            // Returning true here would trigger requestStripUpdate on a dead IC and cause
            // confusing spurious strip clears visible during debugging.
            //
            // Log at debug level so a developer can detect the case where the mirror always
            // returns false due to a bug (strip silently frozen with stale candidates).
            Log.d(TAG, "reconcile: ic is null (field dismissed or binder died); mirror reset, evaluation skipped")
            reset()
            return false
        }

        // Re-seed text from IC to pick up changes we did not author. The selection
        // coordinates from the platform notification are the ground truth; apply them after
        // seeding so reset() inside seed() does not clobber them.
        seed(ic)
        selStart = newSelStart
        selEnd   = newSelEnd
        return true
    }

    // ── Mutation helpers (called by ComposingTextManager) ─────────────────────

    /**
     * Append [text] to the mirror's [textBefore], reflecting a committed character.
     *
     * This is an optimistic update: we update the mirror immediately on commit rather than
     * waiting for [onUpdateSelection] so that back-to-back keystrokes see a consistent view.
     */
    fun appendCommitted(text: CharSequence) {
        textBefore = textBefore.toString() + text.toString()
    }

    /**
     * Reflect a deletion of [codePoints] Unicode code points before the cursor in the mirror.
     *
     * Operates on code points, not [Char]s, so surrogate pairs and emoji are handled correctly.
     */
    fun deleteBeforeCodePoints(codePoints: Int) {
        val s = textBefore.toString()
        var remaining = codePoints
        var endIdx = s.length
        while (remaining > 0 && endIdx > 0) {
            val cp = Character.codePointBefore(s, endIdx)
            endIdx -= Character.charCount(cp)
            remaining--
        }
        textBefore = s.substring(0, endIdx)
    }

    /** Mark that a batch edit is open; suppresses re-entrant [reconcile] calls. */
    fun onBatchEditBegin() { isMidBatchEdit = true }

    /** Mark that a batch edit closed. */
    fun onBatchEditEnd() { isMidBatchEdit = false }

    // ── Reset ─────────────────────────────────────────────────────────────────

    private fun reset() {
        textBefore = ""
        textAfter  = ""
        selStart   = -1
        selEnd     = -1
    }

    companion object {
        /** Maximum characters buffered on each side of the cursor. */
        const val MAX_MIRROR_LENGTH = 500
        private const val TAG = "InputConnectionMirror"
    }
}
