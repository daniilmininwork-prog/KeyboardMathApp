package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [KeyboardController].
 *
 * The controller is pure dispatch logic with no Android UI dependencies, so Robolectric is
 * used only for [EditorInfo] construction. All [InputConnection] interactions are verified
 * through [FakeInputConnection].
 *
 * Acceptance criteria covered (T0.5 + T1.5):
 *   - A single controller instance is reconfigured per field (never recreated).
 *   - configure() with restarting=true preserves keyboard state.
 *   - configure() with restarting=false picks the mode from EditorInfo inputType.
 *   - Alpha keys go through setComposingText; space/numbers commit directly.
 *   - Shift is one-shot: upper for one key, then reverts to lower.
 *   - Backspace uses deleteSurroundingTextInCodePoints.
 *   - Batch edits are balanced: beginBatchEdit and endBatchEdit appear in matched pairs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardControllerTest {

    private lateinit var controller: KeyboardController
    private lateinit var ic: FakeInputConnection

    @Before
    fun setUp() {
        controller = KeyboardController()
        ic = FakeInputConnection()
    }

    // ── configure() — per-field reconfiguration ───────────────────────────────

    @Test
    fun configure_alphaField_setsAlphaLowerState() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), restarting = false)

        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun configure_numericField_setsNumericState() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_NUMBER), restarting = false)

        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    @Test
    fun configure_phoneField_setsNumericState() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_PHONE), restarting = false)

        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    @Test
    fun configure_restarting_preservesCurrentState() {
        // Put controller into upper state.
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())

        // A restarting configure (same field briefly hidden) must not reset the state.
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), restarting = true)

        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun configure_notRestarting_resetsState() {
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())

        // A fresh field focus resets the keyboard to the mode implied by the field type.
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), restarting = false)

        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    // ── handleKey — composing-text for alpha input ────────────────────────────

    @Test
    fun handleKey_alphaChar_usesSetComposingText() {
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        // Alpha input must go through setComposingText, not commitText.
        assertTrue("Expected setComposingText call", ic.calls.any { it.startsWith("setComposingText(a") })
        assertEquals("a", controller.composingText())
    }

    @Test
    fun handleKey_multipleAlphaChars_accumulateInComposingRegion() {
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.handleKey(Key(KeyCode.Char('i'), "i"), ic)

        // The composing buffer should contain "hi" and each keystroke issued setComposingText.
        assertEquals("hi", controller.composingText())
        val composingCalls = ic.calls.filter { it.startsWith("setComposingText") }
        assertEquals(2, composingCalls.size)
        assertTrue(composingCalls[0].startsWith("setComposingText(h"))
        assertTrue(composingCalls[1].startsWith("setComposingText(hi"))
    }

    @Test
    fun handleKey_spaceAfterAlpha_finishesComposingThenCommitsSpace() {
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.handleKey(Key(KeyCode.Char('i'), "i"), ic)
        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(ic.calls.any { it == "finishComposingText()" })
        assertTrue(ic.calls.any { it.startsWith("commitText( ") })
        assertEquals("", controller.composingText())
    }

    @Test
    fun handleKey_numericChar_commitsDirectly_noComposing() {
        controller.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Char('5'), "5"), ic)

        // Numeric keys must use commitText, not setComposingText.
        assertTrue(ic.calls.any { it.startsWith("commitText(5") })
        assertTrue(ic.calls.none { it.startsWith("setComposingText") })
        assertEquals("", controller.composingText())
    }

    @Test
    fun handleKey_symbolChar_commitsDirectly_noComposing() {
        controller.handleKey(Key(KeyCode.SwitchToSymbols, "#+=", isSpecial = true), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Char('+'), "+"), ic)

        assertTrue(ic.calls.any { it.startsWith("commitText(+") })
        assertTrue(ic.calls.none { it.startsWith("setComposingText") })
    }

    // ── handleKey — backspace with code-point deletion ────────────────────────

    @Test
    fun handleKey_backspace_usesDeletingSurroundingTextInCodePoints() {
        controller.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        assertTrue(
            "Backspace must use deleteSurroundingTextInCodePoints",
            ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" },
        )
    }

    @Test
    fun handleKey_backspace_whileComposing_trimsComposingBuffer() {
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.handleKey(Key(KeyCode.Char('i'), "i"), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        // After backspace the composing buffer should be "h" and setComposingText should be called.
        assertEquals("h", controller.composingText())
        assertTrue(ic.calls.any { it.startsWith("setComposingText(h") })
    }

    @Test
    fun handleKey_backspace_emptyComposing_deletesCommittedText() {
        controller.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        assertTrue(ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" })
    }

    // ── Batch-edit balance ────────────────────────────────────────────────────

    @Test
    fun handleKey_alphaChar_batchEditsAreBalanced() {
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals("beginBatchEdit and endBatchEdit must be balanced", begins, ends)
        assertTrue("At least one batch-edit pair expected", begins >= 1)
    }

    @Test
    fun handleKey_space_batchEditsAreBalanced() {
        controller.handleKey(Key(KeyCode.Char('w'), "w"), ic)
        controller.handleKey(Key(KeyCode.Space, ""), ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals(begins, ends)
    }

    @Test
    fun handleKey_backspace_batchEditsAreBalanced() {
        controller.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals(begins, ends)
    }

    // ── Shift state machine ───────────────────────────────────────────────────

    @Test
    fun handleKey_char_commitsUpperWhenAlphaUpper_thenReverts() {
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        assertTrue(ic.calls.any { it.startsWith("setComposingText(A") })
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun handleKey_backspace_noICcall_when_noOp() {
        // Backspace on empty is still communicated to the IC (editor decides if there is
        // something to delete behind the cursor). No crash expected.
        controller.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)
        assertTrue(ic.calls.isNotEmpty())
    }

    @Test
    fun shift_singlePress_goesUpperThenDoubleTapLocks() {
        // Two back-to-back shift presses within the double-tap window promote to LOCKED,
        // not back to LOWER. That is the intended tri-state behavior.
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())

        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())

        // Second immediate tap: double-tap detected → LOCKED (still ALPHA_UPPER).
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())  // LOCKED → still upper
        assertEquals(dev.tally.keyboard.engine.ShiftState.LOCKED, controller.currentShiftState())
    }

    // ── deleteWordBefore (T1.9) ───────────────────────────────────────────────

    @Test
    fun deleteWordBefore_delegatesToComposingManager_usesDeleteSurrounding() {
        // Seed the mirror with a word so the manager has context.
        controller.mirror.appendCommitted("hello")

        controller.deleteWordBefore(ic)

        assertTrue("Word delete must use deleteSurroundingTextInCodePoints",
            ic.calls.any { it.contains("deleteSurroundingTextInCodePoints") })
    }

    @Test
    fun deleteWordBefore_batchEditsAreBalanced() {
        controller.mirror.appendCommitted("test")

        controller.deleteWordBefore(ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals("Batch edits for word delete must be balanced", begins, ends)
    }

    // ── Mode switches (no InputConnection content calls) ─────────────────────

    @Test
    fun handleKey_switchToNumeric_changesStateOnly() {
        controller.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)

        // Only batch-edit calls are allowed; no character committed.
        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue(meaningful.isEmpty())
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    @Test
    fun handleKey_switchToAlpha_changesStateOnly() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_NUMBER), restarting = false)
        controller.handleKey(Key(KeyCode.SwitchToAlpha, "ABC", isSpecial = true), ic)

        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue(meaningful.isEmpty())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun handleKey_switchToSymbols_changesStateOnly() {
        controller.handleKey(Key(KeyCode.SwitchToSymbols, "#+=", isSpecial = true), ic)

        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue(meaningful.isEmpty())
        assertEquals(KeyboardState.SYMBOLS, controller.currentState())
    }

    // ── Autocorrect on space (PHASE 1a) ───────────────────────────────────────

    @Test
    fun handleKey_space_appliesHighConfidenceAutocorrect() {
        // Type "teh" into the composing region.
        controller.handleKey(Key(KeyCode.Char('t'), "t"), ic)
        controller.handleKey(Key(KeyCode.Char('e'), "e"), ic)
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)

        // Strip surfaced a high-confidence correction "the" for the typed "teh".
        controller.setAutocorrectCandidate(autocorrect("the", confidence = 0.95f))
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        // The composing region must be replaced with "the", then a space committed → "the ".
        assertTrue(
            "Composing region must be replaced with the correction",
            ic.calls.any { it.startsWith("setComposingText(the") },
        )
        assertTrue("Replacement must be finished", ic.calls.any { it == "finishComposingText()" })
        assertTrue("Trailing space must be committed", ic.calls.any { it.startsWith("commitText( ") })
        assertEquals("", controller.composingText())
        // The typed word "teh" must never be committed verbatim.
        assertTrue(
            "Typed 'teh' must not be committed",
            ic.calls.none { it.startsWith("commitText(teh") },
        )
    }

    @Test
    fun handleKey_space_autocorrectDisabled_preservesTypedWord() {
        controller.setAutocorrectEnabled(false)

        controller.handleKey(Key(KeyCode.Char('t'), "t"), ic)
        controller.handleKey(Key(KeyCode.Char('e'), "e"), ic)
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)

        // Even with a confident candidate present, the disabled flag must suppress replacement.
        controller.setAutocorrectCandidate(autocorrect("the", confidence = 0.99f))
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        // The typed word is finished as-is (not replaced) and a plain space is committed.
        assertTrue(
            "Disabled autocorrect must not replace the composing region",
            ic.calls.none { it.startsWith("setComposingText(the") },
        )
        assertTrue("Composing word must be finished verbatim", ic.calls.any { it == "finishComposingText()" })
        assertTrue("Plain space must be committed", ic.calls.any { it.startsWith("commitText( ") })
        assertEquals("", controller.composingText())
    }

    @Test
    fun handleKey_space_lowConfidenceCandidate_doesNotAutocorrect() {
        controller.handleKey(Key(KeyCode.Char('t'), "t"), ic)
        controller.handleKey(Key(KeyCode.Char('e'), "e"), ic)
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)

        // Below the 0.85 gate → leave the typed word alone.
        controller.setAutocorrectCandidate(autocorrect("the", confidence = 0.50f))
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(
            "Low-confidence candidate must not be applied",
            ic.calls.none { it.startsWith("setComposingText(the") },
        )
        assertTrue("Plain space must be committed", ic.calls.any { it.startsWith("commitText( ") })
    }

    @Test
    fun handleKey_space_candidateEqualsTypedWord_doesNotAutocorrect() {
        controller.handleKey(Key(KeyCode.Char('t'), "t"), ic)
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.handleKey(Key(KeyCode.Char('e'), "e"), ic)

        // Candidate matches what was typed (case-insensitive) → nothing to correct.
        controller.setAutocorrectCandidate(autocorrect("The", confidence = 0.99f))
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(
            "Identical candidate must not trigger a replace",
            ic.calls.none { it.startsWith("setComposingText(") },
        )
        assertTrue("Plain space must be committed", ic.calls.any { it.startsWith("commitText( ") })
    }

    @Test
    fun handleKey_space_candidateClearedAfterUse() {
        controller.handleKey(Key(KeyCode.Char('t'), "t"), ic)
        controller.handleKey(Key(KeyCode.Char('e'), "e"), ic)
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.setAutocorrectCandidate(autocorrect("the", confidence = 0.95f))
        controller.handleKey(Key(KeyCode.Space, ""), ic)

        // Type a new word without setting a fresh candidate; the stale one must not reapply.
        controller.handleKey(Key(KeyCode.Char('x'), "x"), ic)
        controller.handleKey(Key(KeyCode.Char('y'), "y"), ic)
        controller.handleKey(Key(KeyCode.Char('z'), "z"), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(
            "Stale candidate from the previous word must not be reapplied",
            ic.calls.none { it.startsWith("setComposingText(the") },
        )
        assertEquals("", controller.composingText())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun editorInfoForType(inputType: Int): EditorInfo =
        EditorInfo().apply { this.inputType = inputType }

    private fun autocorrect(text: String, confidence: Float): dev.tally.keyboard.engine.Suggestion =
        dev.tally.keyboard.engine.Suggestion(
            kind = dev.tally.keyboard.engine.SuggestionKind.AUTOCORRECT,
            text = text,
            score = 0f,
            confidence = confidence,
        )
}

/**
 * Minimal [android.view.inputmethod.InputConnection] fake that records every call as a string.
 * Only the methods exercised by [KeyboardController] are overridden.
 *
 * [BaseInputConnection] requires a non-null [View] target on the constructor; we supply a
 * real view created from the application context to satisfy the platform constraint.
 */
internal class FakeInputConnection : android.view.inputmethod.BaseInputConnection(
    android.widget.TextView(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
    false,
) {

    val calls = mutableListOf<String>()

    override fun beginBatchEdit(): Boolean {
        calls += "beginBatchEdit()"
        return true
    }

    override fun endBatchEdit(): Boolean {
        calls += "endBatchEdit()"
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        calls += "setComposingText($text,$newCursorPosition)"
        return true
    }

    override fun finishComposingText(): Boolean {
        calls += "finishComposingText()"
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        calls += "commitText($text,$newCursorPosition)"
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        calls += "deleteSurroundingText($beforeLength,$afterLength)"
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        calls += "deleteSurroundingTextInCodePoints($beforeLength,$afterLength)"
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        calls += "performEditorAction($actionCode)"
        return true
    }
}
