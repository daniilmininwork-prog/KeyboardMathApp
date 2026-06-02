package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CursorController].
 *
 * Verifies the dead zone, step counting, direction, selection mode flag, and UP-gesture
 * detection — all without any Android imports.
 */
class CursorControllerTest {

    // Fixed, density-independent values so arithmetic is obvious.
    private val deadZone = 24f
    private val step = 16f

    private lateinit var controller: CursorController

    @BeforeEach
    fun setUp() {
        controller = CursorController(deadZonePx = deadZone, stepPx = step)
    }

    // ── Dead-zone suppression ─────────────────────────────────────────────────

    @Test
    fun noMovement_noSteps() {
        controller.onSpaceDown(100f)
        assertEquals(0, controller.onMove(100f))
        assertFalse(controller.gestureActive)
    }

    @Test
    fun movementBelowDeadZone_noSteps() {
        controller.onSpaceDown(100f)
        // Move just 1 px short of the dead zone.
        assertEquals(0, controller.onMove(100f + deadZone - 1f))
        assertFalse(controller.gestureActive)
    }

    @Test
    fun movementAtDeadZone_activatesGesture() {
        controller.onSpaceDown(100f)
        // Exactly at the dead zone boundary — the gesture should activate.
        controller.onMove(100f + deadZone)
        assertTrue(controller.gestureActive)
    }

    // ── Step counting — rightward ─────────────────────────────────────────────

    @Test
    fun oneStepRight_afterDeadZone() {
        controller.onSpaceDown(0f)
        // Travel = dead zone + 1 step → exactly 1 step right.
        val steps = controller.onMove(deadZone + step)
        assertEquals(1, steps)
    }

    @Test
    fun twoStepsRight_twoFullStepBoundariesCrossed() {
        controller.onSpaceDown(0f)
        val steps = controller.onMove(deadZone + step * 2)
        assertEquals(2, steps)
    }

    @Test
    fun threeStepsRight_acrossMultipleCalls() {
        controller.onSpaceDown(0f)
        // Accumulate steps across two move calls.
        val s1 = controller.onMove(deadZone + step)         // 1 step
        val s2 = controller.onMove(deadZone + step * 3)     // 2 more steps (total 3)
        assertEquals(1, s1)
        assertEquals(2, s2)
    }

    // ── Step counting — leftward ──────────────────────────────────────────────

    @Test
    fun oneStepLeft_afterDeadZone() {
        controller.onSpaceDown(200f)
        // Move leftward: deadZone + 1 step left of the down point.
        val steps = controller.onMove(200f - deadZone - step)
        assertEquals(-1, steps)
    }

    @Test
    fun twoStepsLeft() {
        controller.onSpaceDown(200f)
        val steps = controller.onMove(200f - deadZone - step * 2)
        assertEquals(-2, steps)
    }

    @Test
    fun leftwardStepsAcrossMultipleCalls() {
        controller.onSpaceDown(200f)
        val s1 = controller.onMove(200f - deadZone - step)
        val s2 = controller.onMove(200f - deadZone - step * 3)
        assertEquals(-1, s1)
        assertEquals(-2, s2)
    }

    // ── Delta correctness ─────────────────────────────────────────────────────

    @Test
    fun onlyDeltaStepsReturned_notCumulative() {
        controller.onSpaceDown(0f)
        // First move: 2 steps right.
        val first = controller.onMove(deadZone + step * 2)
        assertEquals(2, first)

        // Same position — no new steps.
        val second = controller.onMove(deadZone + step * 2)
        assertEquals(0, second)

        // One more step.
        val third = controller.onMove(deadZone + step * 3)
        assertEquals(1, third)
    }

    // ── onUp behaviour ────────────────────────────────────────────────────────

    @Test
    fun onUp_noGesture_returnsFalse() {
        controller.onSpaceDown(100f)
        // No move beyond dead zone.
        assertFalse(controller.onUp())
    }

    @Test
    fun onUp_gestureActive_returnsTrue() {
        controller.onSpaceDown(0f)
        controller.onMove(deadZone + step)  // activate gesture
        assertTrue(controller.onUp())
    }

    @Test
    fun onMove_afterUp_returnsZero() {
        controller.onSpaceDown(0f)
        controller.onMove(deadZone + step)
        controller.onUp()
        // Further moves after UP should produce nothing.
        assertEquals(0, controller.onMove(deadZone + step * 5))
    }

    // ── Selection mode ────────────────────────────────────────────────────────

    @Test
    fun selectionMode_defaultFalse() {
        assertFalse(controller.selectionMode)
    }

    @Test
    fun selectionMode_setBeforeDown_preservedDuringGesture() {
        controller.selectionMode = true
        controller.onSpaceDown(0f)
        controller.onMove(deadZone + step)
        assertTrue(controller.selectionMode)
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun reset_clearsGestureActiveAndStepState() {
        controller.onSpaceDown(0f)
        controller.onMove(deadZone + step * 3)
        controller.reset()

        assertFalse(controller.gestureActive)
        // After reset, a fresh down+move should count from scratch.
        controller.onSpaceDown(0f)
        val steps = controller.onMove(deadZone + step)
        assertEquals(1, steps)
    }

    @Test
    fun reset_noStepsAfterReset_withoutFreshDown() {
        controller.onSpaceDown(0f)
        controller.onMove(deadZone + step * 2)
        controller.reset()
        // Move without a new onSpaceDown — controller should not produce steps.
        assertEquals(0, controller.onMove(deadZone + step * 5))
    }
}
