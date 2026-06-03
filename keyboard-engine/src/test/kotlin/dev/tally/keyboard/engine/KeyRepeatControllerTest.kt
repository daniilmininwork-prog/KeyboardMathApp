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

    // ── Parameterised by BackspaceSpeed (Stage 2) ─────────────────────────────

    /**
     * NORMAL must reproduce the pre-setting hardcoded schedule exactly so an existing install
     * deletes at the same pace as before the preference existed.
     */
    @Test
    fun normalSpeed_reproducesLegacyDefaults() {
        val ctrl = BackspaceSpeed.NORMAL.newController()
        assertEquals(KeyRepeatController.INITIAL_DELAY_MS, ctrl.initialDelayMs)
        assertEquals(KeyRepeatController.MIN_INTERVAL_MS, ctrl.minIntervalMs)
        assertEquals(KeyRepeatController.STEP_FACTOR, ctrl.stepFactor, 0.0)
        assertEquals(KeyRepeatController.INITIAL_DELAY_MS, ctrl.nextIntervalMs())
    }

    /** Each speed's controller starts its schedule at that speed's initial delay. */
    @Test
    fun eachSpeed_firstIntervalMatchesItsInitialDelay() {
        BackspaceSpeed.entries.forEach { speed ->
            val ctrl = speed.newController()
            assertEquals(
                speed.initialDelayMs,
                ctrl.nextIntervalMs(),
                "First interval for $speed must equal its initialDelayMs",
            )
        }
    }

    /**
     * Ordering invariant across speeds: SLOW waits longer before the first delete and bottoms
     * out at a higher floor than NORMAL, which in turn is slower than FAST. This is what makes
     * the Slow/Normal/Fast labels meaningful.
     */
    @Test
    fun speeds_areOrderedSlowToFast() {
        val slow   = BackspaceSpeed.SLOW
        val normal = BackspaceSpeed.NORMAL
        val fast   = BackspaceSpeed.FAST

        assertTrue(slow.initialDelayMs > normal.initialDelayMs, "SLOW initial delay > NORMAL")
        assertTrue(normal.initialDelayMs > fast.initialDelayMs, "NORMAL initial delay > FAST")

        assertTrue(slow.minIntervalMs > normal.minIntervalMs, "SLOW floor > NORMAL floor")
        assertTrue(normal.minIntervalMs > fast.minIntervalMs, "NORMAL floor > FAST floor")
    }

    /** Every speed still honours the controller contract: floor is reached and held. */
    @Test
    fun everySpeed_reachesAndHoldsItsFloor() {
        BackspaceSpeed.entries.forEach { speed ->
            val ctrl = speed.newController()
            repeat(40) { ctrl.nextIntervalMs() }
            val a = ctrl.nextIntervalMs()
            val b = ctrl.nextIntervalMs()
            assertEquals(speed.minIntervalMs, a, "$speed must settle at its floor")
            assertEquals(a, b, "$speed floor must be constant")
        }
    }
}
