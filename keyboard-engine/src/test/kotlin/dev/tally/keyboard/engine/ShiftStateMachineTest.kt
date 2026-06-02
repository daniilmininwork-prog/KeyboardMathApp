package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ShiftStateMachine].
 *
 * All timing-dependent tests use a mutable fake clock so no real sleeps are needed.
 * The machine is pure JVM with no Android dependencies.
 *
 * Acceptance criteria covered (T1.7):
 *   - OFF → SHIFTED on shift press.
 *   - SHIFTED → OFF on char typed (one-shot).
 *   - LOCKED unaffected by char typed (sustained).
 *   - SHIFTED → LOCKED on double-tap within window.
 *   - SHIFTED → OFF on second tap outside window (cancel latch).
 *   - LOCKED → OFF on shift press.
 *   - Auto-caps promotes OFF → SHIFTED without recording a tap time.
 *   - Auto-caps does not block double-tap from OFF-initiated SHIFTED state.
 *   - applyAutoCaps(0) clears auto-capped SHIFTED; leaves user-SHIFTED alone.
 *   - forceState sets state and clears tap timestamp.
 */
class ShiftStateMachineTest {

    private var fakeTime = 0L
    private lateinit var machine: ShiftStateMachine

    @BeforeEach
    fun setUp() {
        fakeTime = 0L
        machine = ShiftStateMachine(clock = { fakeTime })
    }

    // ── Basic OFF→SHIFTED→OFF cycle ──────────────────────────────────────────

    @Test
    fun initialState_isOff() {
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun shiftPress_fromOff_movesToShifted() {
        machine.onShiftPressed()
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    @Test
    fun charTyped_fromShifted_reverts_toOff() {
        machine.onShiftPressed()
        machine.onCharTyped()
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun charTyped_fromOff_staysOff() {
        machine.onCharTyped()
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun charTyped_fromLocked_staysLocked() {
        doubleTapWithinWindow()
        assertEquals(ShiftState.LOCKED, machine.state)
        machine.onCharTyped()
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    // ── Second tap on SHIFTED ────────────────────────────────────────────────

    @Test
    fun secondShiftPress_fromShifted_outsideWindow_cancelsLatch() {
        machine.onShiftPressed()
        fakeTime = 400L  // beyond the 300 ms window
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun secondShiftPress_fromShifted_withinWindow_locks() {
        machine.onShiftPressed()
        fakeTime = 100L  // inside the 300 ms window
        machine.onShiftPressed()
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    @Test
    fun doubleTapExactlyAtWindowBoundary_locks() {
        machine.onShiftPressed()
        fakeTime = 300L  // exactly at the boundary — included
        machine.onShiftPressed()
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    @Test
    fun doubleTapOneMillisecondPastWindow_cancels() {
        machine.onShiftPressed()
        fakeTime = 301L  // one ms past — excluded
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    // ── LOCKED release ───────────────────────────────────────────────────────

    @Test
    fun shiftPress_fromLocked_releasesLock() {
        doubleTapWithinWindow()
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    // ── SHIFTED → LOCKED → OFF full cycle ────────────────────────────────────

    @Test
    fun fullCycle_off_shifted_locked_off() {
        assertEquals(ShiftState.OFF, machine.state)
        machine.onShiftPressed()
        assertEquals(ShiftState.SHIFTED, machine.state)
        fakeTime = 50L
        machine.onShiftPressed()
        assertEquals(ShiftState.LOCKED, machine.state)
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    // ── After locking, a new shift press from OFF goes to SHIFTED (not locked again) ──

    @Test
    fun afterReleasingLock_singleShiftPress_goesBackToShifted() {
        doubleTapWithinWindow()
        machine.onShiftPressed()  // release lock → OFF
        machine.onShiftPressed()  // single tap → SHIFTED
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    // ── Auto-caps ────────────────────────────────────────────────────────────

    @Test
    fun applyAutoCaps_nonZeroFlags_fromOff_promotesToShifted() {
        machine.applyAutoCaps(CAP_SENTENCES)
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    @Test
    fun applyAutoCaps_nonZeroFlags_fromLocked_doesNothing() {
        doubleTapWithinWindow()
        machine.applyAutoCaps(CAP_SENTENCES)
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    @Test
    fun applyAutoCaps_zero_fromAutoCappedShifted_resetsToOff() {
        machine.applyAutoCaps(CAP_SENTENCES)
        machine.applyAutoCaps(0)
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun applyAutoCaps_zero_fromUserShifted_doesNotReset() {
        machine.onShiftPressed()  // user tap
        machine.applyAutoCaps(0)  // should not interfere with user's latch
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    @Test
    fun autoCapsShifted_doesNotCountAsTapForDoubleTap() {
        // Auto-cap → SHIFTED. A single user shift tap should cancel (not lock).
        machine.applyAutoCaps(CAP_SENTENCES)
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun afterAutoCaps_userDoubleTap_locks() {
        // Auto-cap → SHIFTED. User presses shift once to cancel, then again quickly to re-shift;
        // that pair is OFF→SHIFTED (first tap) and SHIFTED→LOCKED (double-tap).
        machine.applyAutoCaps(CAP_SENTENCES)
        machine.onShiftPressed()           // cancel autocap → OFF
        assertEquals(ShiftState.OFF, machine.state)
        machine.onShiftPressed()           // OFF → SHIFTED (first user tap)
        fakeTime = 100L
        machine.onShiftPressed()           // SHIFTED → LOCKED (within window)
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    @Test
    fun applyAutoCaps_multipleCallsWithSameFlags_idempotent() {
        machine.applyAutoCaps(CAP_SENTENCES)
        machine.applyAutoCaps(CAP_SENTENCES)
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    // ── forceState ───────────────────────────────────────────────────────────

    @Test
    fun forceState_off_setsOffAndClearsTapTime() {
        machine.onShiftPressed()
        machine.forceState(ShiftState.OFF)
        assertEquals(ShiftState.OFF, machine.state)
        // After forcing OFF, a single subsequent shift tap should go to SHIFTED (not confused by old tap time).
        machine.onShiftPressed()
        assertEquals(ShiftState.SHIFTED, machine.state)
    }

    @Test
    fun forceState_shifted_setsShifted_treatedAsAutoCaps() {
        // forceState(SHIFTED) marks the state as auto-capped so that a single user tap
        // results in OFF (cancel), not double-tap lock.
        machine.forceState(ShiftState.SHIFTED)
        assertEquals(ShiftState.SHIFTED, machine.state)
        machine.onShiftPressed()
        assertEquals(ShiftState.OFF, machine.state)
    }

    @Test
    fun forceState_locked_setsLocked() {
        machine.forceState(ShiftState.LOCKED)
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    // ── Char does not affect locked when interleaved with shift presses ──────

    @Test
    fun charTyped_betweenTwoShiftPresses_resetsDoubleTapWindow() {
        // First shift → SHIFTED; char typed → OFF. Second shift → SHIFTED again (not locked,
        // because the char interrupted the double-tap sequence).
        machine.onShiftPressed()                      // → SHIFTED
        machine.onCharTyped()                         // → OFF
        fakeTime = 50L                                // still within what would have been the window
        machine.onShiftPressed()                      // → SHIFTED (fresh first tap from OFF)
        fakeTime = 100L
        machine.onShiftPressed()                      // fast second tap → LOCKED
        assertEquals(ShiftState.LOCKED, machine.state)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun doubleTapWithinWindow() {
        machine.onShiftPressed()
        fakeTime += 100L
        machine.onShiftPressed()
    }

    private companion object {
        // Mirror Android's TextUtils.CAP_MODE_SENTENCES value (1) without importing Android.
        const val CAP_SENTENCES = 1
        const val CAP_WORDS = 2
        const val CAP_CHARACTERS = 4
    }
}
