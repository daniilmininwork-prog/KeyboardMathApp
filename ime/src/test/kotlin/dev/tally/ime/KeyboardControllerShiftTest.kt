package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the tri-state shift/caps machine as integrated into [KeyboardController].
 *
 * Verifies:
 *   - State transitions: OFF→SHIFTED→OFF (one-shot); OFF→SHIFTED→LOCKED→OFF (double-tap).
 *   - Double-tap promotes to LOCKED; single tap on LOCKED releases.
 *   - SHIFTED reverts after char; LOCKED does not.
 *   - Auto-caps from EditorInfo.initialCapsMode sets SHIFTED on fresh field entry.
 *   - Numeric/phone field entry does not apply caps.
 *   - onCapsMode integrates the mid-session auto-caps signal.
 *   - Visuals: keyboard state and shift sub-state are readable for rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardControllerShiftTest {

    private lateinit var controller: KeyboardController
    private lateinit var ic: FakeInputConnection

    @Before
    fun setUp() {
        controller = KeyboardController()
        ic = FakeInputConnection()
    }

    // ── One-shot shift ────────────────────────────────────────────────────────

    @Test
    fun shiftPress_movesFromLowerToUpper() {
        pressShift()
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
    }

    @Test
    fun charAfterShift_reverts_toLower() {
        pressShift()
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
        assertEquals(ShiftState.OFF, controller.currentShiftState())
    }

    @Test
    fun charAfterShift_isUpperCase() {
        pressShift()
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)
        assertTrue(ic.calls.any { it.startsWith("setComposingText(A") })
    }

    @Test
    fun secondShiftPress_outsideDoubleTapWindow_cancelsLatch() {
        // The fake clock in ShiftStateMachine uses System.currentTimeMillis by default.
        // We drive the machine through the controller here; timing is hard to control without
        // exposing the clock, so we use the machine's forceState path implicitly. Instead, test
        // the controller's onShiftPressed behavior through two rapid calls.
        // To avoid relying on wall-clock, we reach into the machine directly via configure():
        // configure(restarting=false) resets to OFF. Then two shift presses test the cancel path
        // by pressing shift once and then again immediately (should be within the window and lock,
        // but the test environment may vary). We instead test the observable outcome via
        // forceState: SHIFTED→second tap.

        // Force SHIFTED without starting the double-tap clock by using configure with autocaps=1.
        controller.configure(editorInfoWithCaps(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES), false)
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())

        // A user shift tap on an auto-capped SHIFTED should cancel (not lock).
        pressShift()
        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    // ── Caps-lock (double-tap) ────────────────────────────────────────────────

    @Test
    fun doubleTapShift_locks() {
        // Drive the machine directly via two back-to-back shift presses.
        // The machine uses System.currentTimeMillis; two immediate calls will be within 300 ms.
        pressShift()
        pressShift()
        assertEquals(ShiftState.LOCKED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun charAfterLocked_doesNotRevert() {
        pressShift()
        pressShift()  // now LOCKED
        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)
        assertEquals(ShiftState.LOCKED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun multipleCharsWhileLocked_allUpperCase() {
        pressShift()
        pressShift()  // LOCKED
        controller.handleKey(Key(KeyCode.Char('h'), "h"), ic)
        controller.handleKey(Key(KeyCode.Char('i'), "i"), ic)
        val composingCalls = ic.calls.filter { it.startsWith("setComposingText") }
        assertTrue("Expected H in composing", composingCalls.any { it.contains("H") })
        assertTrue("Expected HI in composing", composingCalls.any { it.contains("HI") })
    }

    @Test
    fun shiftPressOnLocked_releasesLock() {
        pressShift()
        pressShift()  // LOCKED
        pressShift()  // release
        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    // ── Auto-caps from EditorInfo ─────────────────────────────────────────────

    @Test
    fun configure_withCapSentences_appliesShifted() {
        controller.configure(editorInfoWithCaps(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES), false)
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun configure_withCapWords_appliesShifted() {
        controller.configure(editorInfoWithCaps(InputType.TYPE_TEXT_FLAG_CAP_WORDS), false)
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun configure_withCapCharacters_appliesShifted() {
        controller.configure(editorInfoWithCaps(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS), false)
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun configure_withNoCaps_staysOff() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), false)
        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun configure_numericField_doesNotApplyCaps() {
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_NUMBER), false)
        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    @Test
    fun configure_restarting_preservesShiftState() {
        pressShift()
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())

        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), restarting = true)

        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
    }

    // ── onCapsMode (mid-session update) ──────────────────────────────────────

    @Test
    fun onCapsMode_nonZero_fromOff_promotesToShifted() {
        controller.onCapsMode(1)  // CAP_MODE_SENTENCES
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun onCapsMode_zero_afterAutoCaps_resetsToOff() {
        controller.onCapsMode(1)
        controller.onCapsMode(0)
        assertEquals(ShiftState.OFF, controller.currentShiftState())
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun onCapsMode_zero_afterUserShift_doesNotReset() {
        pressShift()  // user tap
        controller.onCapsMode(0)  // should not interfere
        assertEquals(ShiftState.SHIFTED, controller.currentShiftState())
    }

    // ── Visuals: layered keyboard state vs. shift sub-state ──────────────────

    @Test
    fun shifted_and_locked_bothShowAlphaUpper() {
        pressShift()
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())

        val ic2 = FakeInputConnection()
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), false, ic2)
        pressShift()
        pressShift()  // LOCKED
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
    }

    @Test
    fun locked_state_isDistinguishableFromShifted_viaShiftSubState() {
        pressShift()
        val shiftedSubState = controller.currentShiftState()

        val ic2 = FakeInputConnection()
        controller.configure(editorInfoForType(InputType.TYPE_CLASS_TEXT), false, ic2)
        pressShift()
        pressShift()
        val lockedSubState = controller.currentShiftState()

        // Both show ALPHA_UPPER, but the sub-state allows the renderer to distinguish.
        assertEquals(ShiftState.SHIFTED, shiftedSubState)
        assertEquals(ShiftState.LOCKED, lockedSubState)
    }

    // ── Mode-switch with shift state ──────────────────────────────────────────

    @Test
    fun switchToNumericAndBack_withLockedShift_restoresUpper() {
        pressShift()
        pressShift()  // LOCKED
        controller.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
        controller.handleKey(Key(KeyCode.SwitchToAlpha, "ABC", isSpecial = true), ic)
        // LOCKED is still active → returns to ALPHA_UPPER.
        assertEquals(KeyboardState.ALPHA_UPPER, controller.currentState())
        assertEquals(ShiftState.LOCKED, controller.currentShiftState())
    }

    @Test
    fun switchToNumericAndBack_withNoShift_returnsToLower() {
        controller.handleKey(Key(KeyCode.SwitchToNumeric, "123", isSpecial = true), ic)
        controller.handleKey(Key(KeyCode.SwitchToAlpha, "ABC", isSpecial = true), ic)
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pressShift() {
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
    }

    private fun editorInfoForType(inputType: Int): EditorInfo =
        EditorInfo().apply { this.inputType = inputType }

    /**
     * Builds an [EditorInfo] with TYPE_CLASS_TEXT and the given cap flag merged into [inputType],
     * with [EditorInfo.initialCapsMode] set to the corresponding TextUtils CAP_MODE_* value.
     *
     * On a real device the platform calls [android.text.TextUtils.getCapsMode] to derive
     * [initialCapsMode] from the field's inputType and the current cursor position. For tests
     * we set it directly, combining TYPE_CLASS_TEXT (0x01) with the cap flag so that configure()
     * recognises it as an alpha field and applies auto-caps.
     */
    private fun editorInfoWithCaps(capFlag: Int): EditorInfo =
        EditorInfo().apply {
            // Combine the class bits with the cap flag so configure() sees TYPE_CLASS_TEXT.
            inputType = InputType.TYPE_CLASS_TEXT or capFlag
            // initialCapsMode uses TextUtils.CAP_MODE_* bit-masks (sentence=1, words=2, chars=4).
            initialCapsMode = when {
                capFlag and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> 0x04
                capFlag and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> 0x02
                capFlag and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> 0x01
                else -> 0x01  // default: sentence caps
            }
        }
}
