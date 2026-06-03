package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the Stage 2 input-timing enums [BackspaceSpeed] and [LongPressDelay].
 *
 * Both are pure JVM, so they need no Robolectric. The key invariants are: the default matches
 * the previously hardcoded behaviour, [fromKey] round-trips and is fail-safe, and the options
 * are ordered so the Slow/Normal/Fast and Short/Medium/Long labels mean what they say.
 */
class InputTimingEnumTest {

    // ── BackspaceSpeed ─────────────────────────────────────────────────────────

    @Test
    fun backspaceSpeed_defaultIsNormal() {
        assertEquals(BackspaceSpeed.NORMAL, BackspaceSpeed.DEFAULT)
    }

    @Test
    fun backspaceSpeed_fromKey_roundTrips() {
        BackspaceSpeed.entries.forEach { speed ->
            assertEquals(speed, BackspaceSpeed.fromKey(speed.name))
        }
    }

    @Test
    fun backspaceSpeed_fromKey_unknownFallsBackToDefault() {
        assertEquals(BackspaceSpeed.DEFAULT, BackspaceSpeed.fromKey("WARP"))
        assertEquals(BackspaceSpeed.DEFAULT, BackspaceSpeed.fromKey(null))
        assertEquals(BackspaceSpeed.DEFAULT, BackspaceSpeed.fromKey(""))
    }

    @Test
    fun backspaceSpeed_newController_carriesSpeedValues() {
        BackspaceSpeed.entries.forEach { speed ->
            val ctrl = speed.newController()
            assertEquals(speed.initialDelayMs, ctrl.initialDelayMs)
            assertEquals(speed.minIntervalMs, ctrl.minIntervalMs)
            assertEquals(speed.stepFactor, ctrl.stepFactor, 0.0)
        }
    }

    // ── LongPressDelay ─────────────────────────────────────────────────────────

    @Test
    fun longPressDelay_defaultIsMedium() {
        assertEquals(LongPressDelay.MEDIUM, LongPressDelay.DEFAULT)
    }

    @Test
    fun longPressDelay_mediumMatchesLegacyHardcodedValue() {
        // KeyPlaneView previously hardcoded the long-press delay at 400 ms; the default option
        // must preserve that exact feel.
        assertEquals(400L, LongPressDelay.MEDIUM.delayMs)
    }

    @Test
    fun longPressDelay_valuesAreInExpectedRanges() {
        // Brief sanity-check of the spec's Short~250 / Medium~400 / Long~600 targets.
        assertEquals(250L, LongPressDelay.SHORT.delayMs)
        assertEquals(400L, LongPressDelay.MEDIUM.delayMs)
        assertEquals(600L, LongPressDelay.LONG.delayMs)
    }

    @Test
    fun longPressDelay_isOrderedShortToLong() {
        assertTrue(LongPressDelay.SHORT.delayMs < LongPressDelay.MEDIUM.delayMs)
        assertTrue(LongPressDelay.MEDIUM.delayMs < LongPressDelay.LONG.delayMs)
    }

    @Test
    fun longPressDelay_fromKey_roundTrips() {
        LongPressDelay.entries.forEach { delay ->
            assertEquals(delay, LongPressDelay.fromKey(delay.name))
        }
    }

    @Test
    fun longPressDelay_fromKey_unknownFallsBackToDefault() {
        assertEquals(LongPressDelay.DEFAULT, LongPressDelay.fromKey("INSTANT"))
        assertEquals(LongPressDelay.DEFAULT, LongPressDelay.fromKey(null))
        assertEquals(LongPressDelay.DEFAULT, LongPressDelay.fromKey(""))
    }
}
