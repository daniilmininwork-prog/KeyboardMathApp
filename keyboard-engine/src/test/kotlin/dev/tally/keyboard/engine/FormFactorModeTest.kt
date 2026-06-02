package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [FormFactorMode] enum and its companion factory.
 */
class FormFactorModeTest {

    @Test
    fun fromKey_normal_returnsNormal() {
        assertEquals(FormFactorMode.NORMAL, FormFactorMode.fromKey("NORMAL"))
    }

    @Test
    fun fromKey_oneHanded_returnsOneHanded() {
        assertEquals(FormFactorMode.ONE_HANDED, FormFactorMode.fromKey("ONE_HANDED"))
    }

    @Test
    fun fromKey_split_returnsSplit() {
        assertEquals(FormFactorMode.SPLIT, FormFactorMode.fromKey("SPLIT"))
    }

    @Test
    fun fromKey_floating_returnsFloating() {
        assertEquals(FormFactorMode.FLOATING, FormFactorMode.fromKey("FLOATING"))
    }

    @Test
    fun fromKey_unknownValue_fallsBackToNormal() {
        assertEquals(FormFactorMode.NORMAL, FormFactorMode.fromKey("bogus"))
    }

    @Test
    fun fromKey_emptyString_fallsBackToNormal() {
        assertEquals(FormFactorMode.NORMAL, FormFactorMode.fromKey(""))
    }

    @Test
    fun fromKey_caseSensitive_wrongCaseReturnsFallback() {
        // Keys are stored and compared case-sensitively (enum.name is upper-case).
        assertEquals(FormFactorMode.NORMAL, FormFactorMode.fromKey("normal"))
    }

    @Test
    fun allModesRoundTripThroughFromKey() {
        FormFactorMode.entries.forEach { mode ->
            assertEquals(mode, FormFactorMode.fromKey(mode.name),
                "fromKey(${mode.name}) must return $mode")
        }
    }
}
