package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KeyRepeatController].
 *
 * Verifies the accelerating repeat schedule: initial delay, step-down behaviour,
 * floor clamping, and reset. The controller is pure JVM — no Handler or Android
 * types are involved, so no Robolectric is needed.
 */
class KeyRepeatControllerTest {

    private lateinit var ctrl: KeyRepeatController

    @BeforeEach
    fun setUp() {
        ctrl = KeyRepeatController(initialDelayMs = 400L, minIntervalMs = 50L, stepFactor = 0.80)
    }

    @Test
    fun firstInterval_isInitialDelay() {
        assertEquals(400L, ctrl.nextIntervalMs())
    }

    @Test
    fun secondInterval_isSmallerThanFirst() {
        ctrl.nextIntervalMs()           // consume first
        val second = ctrl.nextIntervalMs()
        assertTrue(second < 400L, "Second interval should be less than initial delay")
    }

    @Test
    fun intervalsDecrease_untilFloor() {
        var prev = ctrl.nextIntervalMs()
        var hitFloor = false
        repeat(20) {
            val next = ctrl.nextIntervalMs()
            assertTrue(next <= prev, "Intervals must be non-increasing (prev=$prev next=$next)")
            if (next == 50L) hitFloor = true
            prev = next
        }
        assertTrue(hitFloor, "Floor of 50 ms must be reached within 20 fires")
    }

    @Test
    fun atFloor_intervalsStayConstant() {
        // Fast-forward past the floor (after ~8 steps at 0.80 factor).
        repeat(15) { ctrl.nextIntervalMs() }
        val a = ctrl.nextIntervalMs()
        val b = ctrl.nextIntervalMs()
        assertEquals(50L, a, "Interval must be at floor after many fires")
        assertEquals(a, b, "Interval must remain constant once floor is reached")
    }

    @Test
    fun reset_restartsScheduleFromBeginning() {
        repeat(10) { ctrl.nextIntervalMs() }
        ctrl.reset()
        assertEquals(400L, ctrl.nextIntervalMs(),
            "After reset, first interval must equal initialDelayMs again")
    }

    @Test
    fun noInterval_exceedsInitialDelay() {
        repeat(30) {
            val v = ctrl.nextIntervalMs()
            assertTrue(v <= 400L, "No interval should exceed initialDelayMs (got $v)")
        }
    }

    @Test
    fun allIntervals_aboveFloor() {
        repeat(30) {
            val v = ctrl.nextIntervalMs()
            assertTrue(v >= 50L, "No interval should fall below minIntervalMs (got $v)")
        }
    }

    @Test
    fun defaults_produceReasonableSchedule() {
        val defaultCtrl = KeyRepeatController()
        assertEquals(KeyRepeatController.INITIAL_DELAY_MS, defaultCtrl.nextIntervalMs())
        val second = defaultCtrl.nextIntervalMs()
        assertTrue(second < KeyRepeatController.INITIAL_DELAY_MS)
        assertTrue(second >= KeyRepeatController.MIN_INTERVAL_MS)
    }

    @Test
    fun customParams_respected() {
        val custom = KeyRepeatController(initialDelayMs = 600L, minIntervalMs = 100L, stepFactor = 0.5)
        assertEquals(600L, custom.nextIntervalMs())
        val second = custom.nextIntervalMs()
        assertTrue(second < 600L)
        assertTrue(second >= 100L)
    }
}
