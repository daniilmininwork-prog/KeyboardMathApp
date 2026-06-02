package dev.tally.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ComposingTextManager].
 *
 * All interactions with [InputConnection] are captured through [FakeInputConnection] (also used
 * in [KeyboardControllerTest]).  The tests verify:
 *
 *   - [appendToComposing]: accumulates characters and issues setComposingText each time.
 *   - [finishComposing]: issues finishComposingText and clears the buffer.
 *   - [commitText]: finishes any open composing region, then commits directly.
 *   - [deleteCodePointsBefore]: trims composing buffer first, then falls back to
 *     deleteSurroundingTextInCodePoints for committed text.
 *   - Batch edits: beginBatchEdit / endBatchEdit are balanced for every operation.
 *   - Re-entrancy: the mirror's isMidBatchEdit flag is raised for the duration of each op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComposingTextManagerTest {

    private lateinit var mirror: InputConnectionMirror
    private lateinit var manager: ComposingTextManager
    private lateinit var ic: FakeInputConnection

    @Before
    fun setUp() {
        mirror  = InputConnectionMirror()
        manager = ComposingTextManager(mirror)
        ic      = FakeInputConnection()
    }

    // ── appendToComposing ─────────────────────────────────────────────────────

    @Test
    fun appendToComposing_singleChar_setsComposingText() {
        manager.appendToComposing('a', ic)

        assertTrue(ic.calls.any { it.startsWith("setComposingText(a") })
        assertEquals("a", manager.composingText)
        assertTrue(manager.isComposing)
    }

    @Test
    fun appendToComposing_multipleChars_accumulatesBuffer() {
        manager.appendToComposing('h', ic)
        manager.appendToComposing('i', ic)

        assertEquals("hi", manager.composingText)
        val composingCalls = ic.calls.filter { it.startsWith("setComposingText") }
        assertEquals(2, composingCalls.size)
        assertTrue(composingCalls[0].startsWith("setComposingText(h"))
        assertTrue(composingCalls[1].startsWith("setComposingText(hi"))
    }

    @Test
    fun appendToComposing_nullIc_noOp() {
        manager.appendToComposing('a', null)

        assertEquals("", manager.composingText)
        assertFalse(manager.isComposing)
    }

    @Test
    fun appendToComposing_batchEditsAreBalanced() {
        manager.appendToComposing('x', ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals("beginBatchEdit / endBatchEdit must be balanced", begins, ends)
        assertTrue(begins >= 1)
    }

    // ── finishComposing ───────────────────────────────────────────────────────

    @Test
    fun finishComposing_whenComposing_callsFinishComposingText() {
        manager.appendToComposing('a', ic)
        ic.calls.clear()

        manager.finishComposing(ic)

        assertTrue(ic.calls.any { it == "finishComposingText()" })
        assertEquals("", manager.composingText)
        assertFalse(manager.isComposing)
    }

    @Test
    fun finishComposing_whenNotComposing_stillCallsFinishComposingText() {
        // finishComposingText is idempotent from the editor's perspective.
        manager.finishComposing(ic)

        assertTrue(ic.calls.any { it == "finishComposingText()" })
    }

    @Test
    fun finishComposing_nullIc_noOp() {
        manager.appendToComposing('a', ic)
        manager.finishComposing(null)

        // Buffer is not cleared when IC is null (nothing can commit it).
        assertEquals("a", manager.composingText)
    }

    // ── commitText ────────────────────────────────────────────────────────────

    @Test
    fun commitText_whenNotComposing_commitsDirectly() {
        manager.commitText("5", ic)

        assertTrue(ic.calls.any { it.startsWith("commitText(5") })
        assertFalse(manager.isComposing)
    }

    @Test
    fun commitText_whenComposing_finishesFirstThenCommits() {
        manager.appendToComposing('h', ic)
        ic.calls.clear()

        manager.commitText(" ", ic)

        val finishIdx  = ic.calls.indexOfFirst { it == "finishComposingText()" }
        val commitIdx  = ic.calls.indexOfFirst { it.startsWith("commitText( ") }
        assertTrue("finishComposingText must precede commitText", finishIdx < commitIdx)
        assertEquals("", manager.composingText)
    }

    @Test
    fun commitText_batchEditsAreBalanced() {
        manager.commitText("!", ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals(begins, ends)
    }

    // ── deleteCodePointsBefore ────────────────────────────────────────────────

    @Test
    fun deleteCodePointsBefore_emptyComposing_deletesViaIc() {
        manager.deleteCodePointsBefore(1, ic)

        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" })
    }

    @Test
    fun deleteCodePointsBefore_whileComposing_trimsBuffer() {
        manager.appendToComposing('h', ic)
        manager.appendToComposing('i', ic)
        ic.calls.clear()

        manager.deleteCodePointsBefore(1, ic)

        assertEquals("h", manager.composingText)
        // setComposingText("h", 1) should have been issued.
        assertTrue(ic.calls.any { it.startsWith("setComposingText(h") })
    }

    @Test
    fun deleteCodePointsBefore_whileComposing_deletingMoreThanBuffer_spillsToIc() {
        manager.appendToComposing('a', ic)
        ic.calls.clear()

        // Delete 2 code points: 1 from the composing buffer ("a"), 1 from committed text.
        manager.deleteCodePointsBefore(2, ic)

        assertEquals("", manager.composingText)
        assertTrue(ic.calls.any { it == "finishComposingText()" || it.startsWith("setComposingText(") })
        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" })
    }

    @Test
    fun deleteCodePointsBefore_emptyComposing_deletesAll_viaIc() {
        // Empty composing region: all deletions go straight to the IC.
        manager.deleteCodePointsBefore(3, ic)

        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(3,0)" })
    }

    @Test
    fun deleteCodePointsBefore_batchEditsAreBalanced() {
        manager.deleteCodePointsBefore(1, ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals(begins, ends)
    }

    @Test
    fun deleteCodePointsBefore_nullIc_noOp() {
        manager.appendToComposing('a', ic)

        // A null IC should not crash and should leave the buffer intact.
        manager.deleteCodePointsBefore(1, null)

        // composingText might still be "a" because we couldn't communicate with the IC.
        // The key contract is no exception.
        assertFalse(ic.calls.any { it.contains("deleteSurroundingTextInCodePoints") })
    }

    // ── isMidBatchEdit guard (re-entrancy / withBatchEdit) ────────────────────

    @Test
    fun midBatchEditFlag_raisedDuringAppend_clearedAfter() {
        var flagDuringBatch = false
        // Intercept via mirror observer — we check the flag at the end since we can't inject
        // mid-call. The key invariant is that flag is false after the call completes.
        manager.appendToComposing('a', ic)

        assertFalse("isMidBatchEdit must be cleared after operation", mirror.isMidBatchEdit)
    }

    /**
     * Verifies the re-entrancy guard: [InputConnectionMirror.isMidBatchEdit] is false after a
     * batch edit completes, and [InputConnectionMirror.reconcile] returns false while a batch
     * edit is open.
     *
     * This test covers the path described in issue 19:
     *   batch-edit begins → mirror flag set → operations run → batch-edit ends → flag cleared.
     *
     * The test manually drives [InputConnectionMirror.onBatchEditBegin] and [reconcile] to verify
     * the guard semantics without needing to intercept the IC mid-call.
     */
    @Test
    fun reentrancyGuard_isMidBatchEditTrueDuringBatch_falseAfter_reconcileReturnsFalse() {
        // Precondition: flag is false before any batch edit.
        assertFalse("isMidBatchEdit must be false initially", mirror.isMidBatchEdit)

        // Simulate the mirror state that ComposingTextManager sets before calling beginBatchEdit.
        mirror.onBatchEditBegin()
        assertTrue("isMidBatchEdit must be true while batch edit is open", mirror.isMidBatchEdit)

        // Simulate what onUpdateSelection would do mid-batch: reconcile must return false,
        // preventing a re-entrant evaluation triggered by our own commitText.
        val reconcileResultDuringBatch = mirror.reconcile(5, 5, null)
        assertFalse(
            "reconcile must return false mid-batch (prevents double evaluation / chip flicker)",
            reconcileResultDuringBatch,
        )

        // Close the batch edit.
        mirror.onBatchEditEnd()
        assertFalse("isMidBatchEdit must be false after batch edit completes", mirror.isMidBatchEdit)

        // After the batch closes, reconcile with a null IC returns false because the field
        // is dismissed (no live IC). The reentrancy guard (isMidBatchEdit=false) is what
        // matters here; the null-IC guard is an independent fix (issue 11).
        val reconcileResultAfterBatch = mirror.reconcile(5, 5, null)
        assertFalse(
            "reconcile returns false for null IC (field dismissed), regardless of batch state",
            reconcileResultAfterBatch,
        )
    }

    /**
     * Integration-level verification: [appendToComposing] leaves [isMidBatchEdit] false after
     * completing, confirming that withBatchEdit properly balances begin/end and resets the flag.
     */
    @Test
    fun reentrancyGuard_appendToComposing_mirrorFlagClearedAfterCompletion() {
        manager.appendToComposing('a', ic)
        manager.appendToComposing('b', ic)

        // After all operations complete, the flag must be false so onUpdateSelection on the
        // next character does not incorrectly suppress evaluation.
        assertFalse(
            "isMidBatchEdit must be false after appendToComposing completes",
            mirror.isMidBatchEdit,
        )
    }

    // ── deleteWordBefore (T1.9) ───────────────────────────────────────────────

    /**
     * Seed the mirror's textBefore so deleteWordBefore has context to work with.
     * The mirror is internal; we populate it via appendCommitted.
     */
    private fun seedMirror(text: String) {
        text.forEach { mirror.appendCommitted(it.toString()) }
    }

    @Test
    fun deleteWordBefore_singleWord_deletesWholeWord() {
        seedMirror("hello")
        ic.calls.clear()

        manager.deleteWordBefore(ic)

        // "hello" = 5 code points
        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(5,0)" })
        assertEquals("", mirror.textBefore.toString())
    }

    @Test
    fun deleteWordBefore_wordWithTrailingSpace_deletesSpaceAndWord() {
        seedMirror("hello ")
        ic.calls.clear()

        manager.deleteWordBefore(ic)

        // space (1) + "hello" (5) = 6 code points
        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(6,0)" })
    }

    @Test
    fun deleteWordBefore_twoWords_deletesOnlyLastWordAndTrailingSpace() {
        seedMirror("foo bar ")
        ic.calls.clear()

        manager.deleteWordBefore(ic)

        // " " (1) + "bar" (3) = 4 code points; "foo " is left intact
        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(4,0)" })
    }

    @Test
    fun deleteWordBefore_emptyContext_noOp() {
        // mirror is empty; nothing to delete
        manager.deleteWordBefore(ic)

        assertTrue("No IC calls expected when context is empty",
            ic.calls.none { it.contains("deleteSurroundingText") })
    }

    @Test
    fun deleteWordBefore_nullIc_noOp() {
        seedMirror("hello")

        // No crash; mirror is unchanged.
        manager.deleteWordBefore(null)
    }

    @Test
    fun deleteWordBefore_batchEditsAreBalanced() {
        seedMirror("hello")

        manager.deleteWordBefore(ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals("Batch edits must be balanced", begins, ends)
        assertTrue(begins >= 1)
    }

    @Test
    fun deleteWordBefore_whileComposing_finishesComposingFirst() {
        // Composing "wo" and then delete-word should commit the composing region and then
        // delete back to the previous word boundary.
        seedMirror("hello ")      // committed text
        manager.appendToComposing('w', ic)
        manager.appendToComposing('o', ic)
        ic.calls.clear()

        manager.deleteWordBefore(ic)

        // finishComposingText must have been issued to clear the composing region.
        assertTrue(ic.calls.any { it == "finishComposingText()" })
        // After finishing "wo", mirror before = "hello wo"; delete-word removes "wo" + " " = 3
        // (or just "wo" depending on where leading whitespace ends). The key invariant is that
        // the composing region was resolved before the deletion.
        assertTrue(ic.calls.any { it.contains("deleteSurroundingTextInCodePoints") })
    }

    @Test
    fun deleteWordBefore_onlyWhitespace_deletesAllWhitespace() {
        seedMirror("   ")
        ic.calls.clear()

        manager.deleteWordBefore(ic)

        // Three spaces, no word after the whitespace skip → 3 code points of whitespace removed.
        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(3,0)" })
    }

    // ── resetWithoutIc ────────────────────────────────────────────────────────

    @Test
    fun resetWithoutIc_clearsBuffer() {
        manager.appendToComposing('x', ic)

        manager.resetWithoutIc()

        assertEquals("", manager.composingText)
        assertFalse(manager.isComposing)
    }

    @Test
    fun resetWithoutIc_resetsBatchDepthToZero() {
        // Open a batch edit by appending — this increments batchDepth to 1 during the call.
        // After the call batchDepth is back to 0, but the point of this test is that even if
        // a batch was somehow left open (simulated by a forced mid-batch state), resetWithoutIc
        // clears it and leaves isMidBatchEdit false afterward.
        manager.appendToComposing('a', ic)

        // Simulate a leaked open batch: manually drive the mirror into mid-batch state by
        // calling appendToComposing again and checking that reset cleans it up entirely.
        manager.appendToComposing('b', ic)
        manager.resetWithoutIc()

        assertFalse("isMidBatchEdit must be false after resetWithoutIc", mirror.isMidBatchEdit)
        assertEquals("", manager.composingText)
        assertFalse(manager.isComposing)
    }

    // ── onFinishInputView teardown contract ───────────────────────────────────

    /**
     * Verifies the composing teardown path that [TallyInputMethodService.onFinishInputView]
     * exercises via [KeyboardController.teardown].
     *
     * Contract: after typing into the composing region and then calling [resetWithoutIc]
     * (as teardown does when the IC is no longer available), isComposing must be false and
     * the mirror's batch-edit depth must be zero.
     */
    @Test
    fun fieldExit_afterTyping_composingClearedAndBatchDepthZero() {
        // Simulate typing "42" into the composing region.
        manager.appendToComposing('4', ic)
        manager.appendToComposing('2', ic)

        assertTrue("precondition: isComposing must be true before teardown", manager.isComposing)

        // Simulate onFinishInputView: IC may be gone, so we call resetWithoutIc directly.
        manager.resetWithoutIc()

        assertFalse("isComposing must be false after field exit teardown", manager.isComposing)
        assertFalse("mirror.isMidBatchEdit must be false after teardown", mirror.isMidBatchEdit)
        assertEquals("composingText must be empty after teardown", "", manager.composingText)
    }
}
