package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for the PHASE 1b typing behaviours in [KeyboardController]:
 *   - Double-space-to-period: two quick Space taps after a word collapse to ". ".
 *   - Auto-cap at sentence start: the field's initialCapsMode raises shift only when the
 *     [KeyboardController.autoCapEnabled] preference is on.
 *
 * Timing for the double-space window is driven through Robolectric's controllable
 * [android.os.SystemClock] shadow so no real sleeps are needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardControllerPhase1bTest {

    private lateinit var controller: KeyboardController
    private lateinit var ic: FakeInputConnection

    @Before
    fun setUp() {
        controller = KeyboardController()
        ic = FakeInputConnection()
    }

    // ── Double-space-to-period ────────────────────────────────────────────────

    @Test
    fun doubleSpaceAfterWord_withinWindow_insertsPeriodSpace() {
        typeWord("hi")
        // First space commits the word and a trailing space.
        controller.handleKey(Key(KeyCode.Space, ""), ic)
        ic.calls.clear()

        // Second space immediately after (same uptime) — within the double-space window.
        controller.handleKey(Key(KeyCode.Space, ""), ic)

        // The trailing space is deleted and ". " committed in its place.
        assertTrue(
            "First space must be deleted before the period is committed",
            ic.calls.any { it == "deleteSurroundingTextInCodePoints(1,0)" },
        )
        assertTrue(
            "Period + space must be committed",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        assertEquals("hi. ", controller.mirror.textBefore.toString())
    }

    @Test
    fun doubleSpace_outsideWindow_insertsTwoLiteralSpaces() {
        typeWord("hi")
        controller.handleKey(Key(KeyCode.Space, ""), ic)

        // Advance the uptime clock (which SystemClock.uptimeMillis reads from in Robolectric)
        // past the double-space window so the second tap is too late to pair.
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(KeyboardController.DOUBLE_SPACE_WINDOW_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertTrue(
            "A late second space must commit a literal space, not a period",
            ic.calls.any { it.startsWith("commitText( ") },
        )
        assertFalse(
            "No period replacement outside the window",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        assertEquals("hi  ", controller.mirror.textBefore.toString())
    }

    @Test
    fun doubleSpace_disabled_insertsTwoLiteralSpaces() {
        controller.setDoubleSpacePeriod(false)
        typeWord("hi")
        controller.handleKey(Key(KeyCode.Space, ""), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertFalse(
            "Disabled gesture must never insert a period",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        assertTrue(ic.calls.any { it.startsWith("commitText( ") })
        assertEquals("hi  ", controller.mirror.textBefore.toString())
    }

    @Test
    fun doubleSpace_afterPunctuation_insertsTwoLiteralSpaces() {
        // Type "hi!" then a space; the char before the trailing space is '!' (not a word char).
        typeWord("hi")
        controller.handleKey(Key(KeyCode.SwitchToSymbols, "#+=", isSpecial = true), ic)
        controller.handleKey(Key(KeyCode.Char('!'), "!"), ic)
        controller.handleKey(Key(KeyCode.Space, ""), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertFalse(
            "Double-space after punctuation must not upgrade to a period",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        // Two literal spaces after the punctuation: "hi!" + " " + " ".
        assertEquals("hi!  ", controller.mirror.textBefore.toString())
    }

    @Test
    fun doubleSpace_pairsOnTouchTime_evenWhenDispatchIsSlow() {
        // The window is measured from the touch-up event time, not from when handleKey runs.
        // Simulate a real double-tap (event times 120 ms apart, inside the window) that is
        // delivered late: advance the processing clock well past the window between the two
        // handleKey calls. The pair must still collapse, proving dispatch latency cannot
        // defeat a genuine fast double-tap — the exact failure the live adb-tap harness hit.
        typeWord("hi")
        val firstTouch = android.os.SystemClock.uptimeMillis()
        controller.handleKey(Key(KeyCode.Space, ""), ic, eventTimeMs = firstTouch)

        // Processing/IPC delay far exceeding the window before the second space is handled.
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(KeyboardController.DOUBLE_SPACE_WINDOW_MS * 5, java.util.concurrent.TimeUnit.MILLISECONDS)
        ic.calls.clear()

        // Second tap's *touch* time is only 120 ms after the first — within the window.
        controller.handleKey(Key(KeyCode.Space, ""), ic, eventTimeMs = firstTouch + 120)

        assertTrue(
            "A fast double-tap must pair on touch time even when delivered late",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        assertEquals("hi. ", controller.mirror.textBefore.toString())
    }

    @Test
    fun typingBetweenSpaces_breaksTheWindow() {
        typeWord("hi")
        controller.handleKey(Key(KeyCode.Space, ""), ic)  // arms the window
        // A character typed before the second space must reset the window, so the space that
        // follows the character cannot pair with the earlier one.
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)
        ic.calls.clear()

        controller.handleKey(Key(KeyCode.Space, ""), ic)

        assertFalse(
            "A character between the two spaces must prevent the period upgrade",
            ic.calls.any { it.startsWith("commitText(. ") },
        )
        assertEquals("hi a ", controller.mirror.textBefore.toString())
    }

    // ── Auto-cap at sentence start ────────────────────────────────────────────

    @Test
    fun autoCapEnabled_capSentencesField_raisesShiftOnEntry() {
        controller.setAutoCapEnabled(true)
        controller.configure(editorInfoWithSentenceCaps(), restarting = false, ic)

        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun autoCapDisabled_capSentencesField_staysLowerOnEntry() {
        controller.setAutoCapEnabled(false)
        controller.configure(editorInfoWithSentenceCaps(), restarting = false, ic)

        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun autoCapEnabled_onCapsMode_raisesShiftMidSession() {
        controller.setAutoCapEnabled(true)
        controller.onCapsMode(1)  // CAP_MODE_SENTENCES — cursor moved to a sentence start

        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun autoCapDisabled_onCapsMode_doesNotRaiseShift() {
        controller.setAutoCapEnabled(false)
        controller.onCapsMode(1)

        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun autoCapDisabled_manualShiftStillWorks() {
        // Opting out of auto-cap must not disable the shift key itself.
        controller.setAutoCapEnabled(false)
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)

        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun typeWord(word: String) {
        for (ch in word) {
            controller.handleKey(Key(KeyCode.Char(ch), ch.toString()), ic)
        }
    }

    private fun editorInfoWithSentenceCaps(): EditorInfo =
        EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            initialCapsMode = 0x01  // TextUtils.CAP_MODE_SENTENCES
        }
}
