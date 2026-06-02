package dev.tally.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [TallyInputMethodService.handleKey].
 *
 * Uses [RecordingInputConnection] to verify that the correct [InputConnection] methods are called
 * for each key type — and that keyboard-state transitions happen correctly — without needing a
 * live IME session.
 *
 * Acceptance criteria covered (T1.5):
 *   - Alpha keys use setComposingText (composing-text pipeline)
 *   - Space commits text directly (finishComposingText then commitText)
 *   - Numeric/symbol keys commit directly without setComposingText
 *   - Backspace uses deleteSurroundingTextInCodePoints (emoji-safe)
 *   - beginBatchEdit / endBatchEdit calls are balanced for every key
 *   - Shift transitions: lower → upper → lower (one-shot)
 *   - Mode-switch keys change internal state without committing characters
 */
@RunWith(AndroidJUnit4::class)
class InputConnectionHandlerTest {

    private lateinit var service: TallyInputMethodService
    private lateinit var ic: RecordingInputConnection

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        service = TallyInputMethodService()
        ic = RecordingInputConnection(View(ctx), true)
    }

    // ── Composing-text for alpha keys ─────────────────────────────────────────

    @Test
    fun alphaLowerKey_usesSetComposingText() {
        service.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        assertTrue("Expected setComposingText call", ic.calls.any { it.startsWith("setComposingText(a") })
        assertTrue("commitText must not appear for alpha", ic.calls.none { it.startsWith("commitText(a") })
    }

    @Test
    fun alphaUpperKey_setComposingWithUpperChar_thenResetsToLower() {
        service.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        service.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        assertTrue("Expected setComposingText(A)", ic.calls.any { it.startsWith("setComposingText(A") })
        // After the upper key the state is back to lower; verify by typing another character.
        val ic2 = RecordingInputConnection(
            View(InstrumentationRegistry.getInstrumentation().targetContext), true,
        )
        service.handleKey(Key(KeyCode.Char('b'), "b"), ic2)
        assertTrue(ic2.calls.any { it.startsWith("setComposingText(ab") || it.startsWith("setComposingText(b") })
    }

    @Test
    fun spaceKey_finishesComposingThenCommitsSpace() {
        service.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        service.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(ic.calls.any { it == "finishComposingText()" })
        assertTrue(ic.calls.any { it.startsWith("commitText( ") })
    }

    @Test
    fun numericCharKey_commitsDirectly_noSetComposingText() {
        service.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)
        ic.calls.clear()

        service.handleKey(Key(KeyCode.Char('5'), "5"), ic)

        assertTrue("Numeric key must use commitText", ic.calls.any { it.startsWith("commitText(5") })
        assertTrue("Numeric key must not use setComposingText", ic.calls.none { it.startsWith("setComposingText") })
    }

    @Test
    fun symbolCharKey_commitsDirectly_noSetComposingText() {
        service.handleKey(Key(KeyCode.SwitchToSymbols, "#+=", isSpecial = true), ic)
        ic.calls.clear()

        service.handleKey(Key(KeyCode.Char('+'), "+"), ic)

        assertTrue(ic.calls.any { it.startsWith("commitText(+") })
        assertTrue(ic.calls.none { it.startsWith("setComposingText") })
    }

    // ── Backspace uses deleteSurroundingTextInCodePoints ──────────────────────

    @Test
    fun backspaceKey_callsDeleteSurroundingTextInCodePoints() {
        service.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        assertTrue(
            "Backspace must use deleteSurroundingTextInCodePoints",
            ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" },
        )
    }

    @Test
    fun multipleBackspaces_eachDeletesOneCodePoint() {
        repeat(3) { service.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic) }

        assertEquals(
            3,
            ic.calls.count { it == "deleteSurroundingTextInCodePoints(1,0)" ||
                it.startsWith("setComposingText(") },
        )
    }

    // ── Batch-edit balance ────────────────────────────────────────────────────

    @Test
    fun batchEditsAreBalancedForAlphaKey() {
        service.handleKey(Key(KeyCode.Char('x'), "x"), ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals("beginBatchEdit and endBatchEdit must match", begins, ends)
        assertTrue("At least one batch-edit pair expected", begins >= 1)
    }

    @Test
    fun batchEditsAreBalancedForBackspace() {
        service.handleKey(Key(KeyCode.Backspace, "⌫", isSpecial = true), ic)

        val begins = ic.calls.count { it == "beginBatchEdit()" }
        val ends   = ic.calls.count { it == "endBatchEdit()" }
        assertEquals(begins, ends)
    }

    // ── Mode switches (no character output) ──────────────────────────────────

    @Test
    fun switchToNumeric_changesStateOnly() {
        service.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)

        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue("Mode switch must not commit characters", meaningful.isEmpty())
    }

    @Test
    fun switchToAlpha_changesStateOnly() {
        service.handleKey(Key(KeyCode.SwitchToAlpha, "ABC", isSpecial = true), ic)

        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue(meaningful.isEmpty())
    }

    @Test
    fun switchToSymbols_changesStateOnly() {
        service.handleKey(Key(KeyCode.SwitchToSymbols, "#+=", isSpecial = true), ic)

        val meaningful = ic.calls.filter { it != "beginBatchEdit()" && it != "endBatchEdit()" && it != "finishComposingText()" }
        assertTrue(meaningful.isEmpty())
    }

    // ── Shift state transitions ───────────────────────────────────────────────

    @Test
    fun shiftToggle_lowerToUpper_upperToLower() {
        // shift → upper
        service.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertTrue("No character committed for shift", ic.calls.none { it.startsWith("commitText(") || it.startsWith("setComposingText(") })

        // shift again → back to lower
        service.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        assertTrue(ic.calls.none { it.startsWith("commitText(") || it.startsWith("setComposingText(") })
    }

    // ── Enter ─────────────────────────────────────────────────────────────────

    @Test
    fun enterKey_producesAtLeastOneIcCall() {
        service.handleKey(Key(KeyCode.Enter, "↵", isSpecial = true), ic)

        assertTrue("Enter must produce at least one IC call", ic.calls.isNotEmpty())
    }
}

// ── Test helper ───────────────────────────────────────────────────────────────

/**
 * A recording [BaseInputConnection] that tracks every method call as a string.
 * Overrides every method relevant to the composing-text pipeline (T1.5).
 */
internal class RecordingInputConnection(target: View, fullEditor: Boolean) :
    BaseInputConnection(target, fullEditor) {

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

    override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
        calls += "sendKeyEvent(${event?.keyCode},${event?.action})"
        return true
    }
}
