package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the RTL bidi-safety contract for math display strings.
 *
 * A math result placed inside an Arabic or Hebrew paragraph must always render LTR.
 * The Unicode Bidi Algorithm (UBA) handles numeric runs well in most cases, but
 * paragraph-level RTL direction can reverse expressions at boundaries. The LRI/PDI
 * isolate marks added by [MathBidiContract.wrapLtr] prevent this reversal.
 *
 * Source: 05-device-framework-compatibility.md §5.1.
 */
class MathBidiContractTest {

    // ── wrapLtr ───────────────────────────────────────────────────────────────

    @Test
    fun wrapLtr_addsLriPrefixAndPdiSuffix() {
        val result = MathBidiContract.wrapLtr("42")
        assertEquals(
            "${MathBidiContract.LRI}42${MathBidiContract.PDI}",
            result,
        )
    }

    @Test
    fun wrapLtr_producesIsWrappedRecognisedString() {
        assertTrue(MathBidiContract.isWrapped(MathBidiContract.wrapLtr("100")))
    }

    @Test
    fun wrapLtr_onEmptyString_producesLriPdiPair() {
        val result = MathBidiContract.wrapLtr("")
        assertEquals(
            "${MathBidiContract.LRI}${MathBidiContract.PDI}",
            result,
        )
        assertTrue(MathBidiContract.isWrapped(result))
    }

    @Test
    fun wrapLtr_preservesDecimalPoint() {
        val result = MathBidiContract.wrapLtr("3.14")
        assertTrue(result.contains("3.14"))
    }

    @Test
    fun wrapLtr_preservesCommaSeparators() {
        val result = MathBidiContract.wrapLtr("1,234,567.89")
        assertTrue(result.contains("1,234,567.89"))
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    fun wrapLtr_isIdempotent_doubleWrapEqualsSingleWrap() {
        val once  = MathBidiContract.wrapLtr("99")
        val twice = MathBidiContract.wrapLtr(once)
        assertEquals(once, twice)
    }

    @Test
    fun wrapLtr_idempotency_holdsForMultipleValues() {
        for (v in listOf("0", "1", "12", "999", "1000000")) {
            val once  = MathBidiContract.wrapLtr(v)
            val twice = MathBidiContract.wrapLtr(once)
            assertEquals(once, twice) { "idempotency failed for value $v" }
        }
    }

    // ── unwrap ────────────────────────────────────────────────────────────────

    @Test
    fun unwrap_recoversOriginalStringAfterWrapLtr() {
        val original = "15"
        assertEquals(original, MathBidiContract.unwrap(MathBidiContract.wrapLtr(original)))
    }

    @Test
    fun unwrap_isNoOpOnPlainString() {
        val plain = "7.5"
        assertEquals(plain, MathBidiContract.unwrap(plain))
    }

    @Test
    fun unwrap_roundTripIsLossless() {
        val original = "1,234.56"
        val roundTripped = MathBidiContract.unwrap(MathBidiContract.wrapLtr(original))
        assertEquals(original, roundTripped)
    }

    @Test
    fun unwrap_ofEmptyWrappedString_returnsEmptyString() {
        val wrapped = MathBidiContract.wrapLtr("")
        assertEquals("", MathBidiContract.unwrap(wrapped))
    }

    // ── isWrapped ─────────────────────────────────────────────────────────────

    @Test
    fun isWrapped_returnsTrueForWrapLtrOutput() {
        assertTrue(MathBidiContract.isWrapped(MathBidiContract.wrapLtr("5")))
    }

    @Test
    fun isWrapped_returnsFalseForPlainNumberStrings() {
        for (v in listOf("0", "42", "3.14", "1,000")) {
            assertFalse(MathBidiContract.isWrapped(v)) { "isWrapped should be false for '$v'" }
        }
    }

    @Test
    fun isWrapped_returnsFalseForEmptyString() {
        assertFalse(MathBidiContract.isWrapped(""))
    }

    @Test
    fun isWrapped_returnsFalseForLriWithoutPdi() {
        val partial = "${MathBidiContract.LRI}42"
        assertFalse(MathBidiContract.isWrapped(partial))
    }

    @Test
    fun isWrapped_returnsFalseForPdiWithoutLri() {
        val partial = "42${MathBidiContract.PDI}"
        assertFalse(MathBidiContract.isWrapped(partial))
    }

    // ── character identity ────────────────────────────────────────────────────

    @Test
    fun lriConstant_isU2066() {
        assertEquals(0x2066, MathBidiContract.LRI.code)
    }

    @Test
    fun pdiConstant_isU2069() {
        assertEquals(0x2069, MathBidiContract.PDI.code)
    }

    @Test
    fun lriAndPdi_areDistinctCharacters() {
        assertNotEquals(MathBidiContract.LRI, MathBidiContract.PDI)
    }

    // ── commit-path contract: unwrap before commitText ────────────────────────

    /**
     * When math results are committed to an editor via InputConnection.commitText
     * the bidi marks must be stripped. This test models the contract:
     *
     *   displayString = wrapLtr(rawValue)    // shown in chip / strip
     *   commitString  = unwrap(displayString) // sent to InputConnection
     *
     * The committed string must equal the raw value, not contain bidi marks.
     */
    @Test
    fun commitPath_unwrapOfWrapLtrEqualsOriginal() {
        val values = listOf("0", "1", "42", "3.14", "1,234.56", "100%")
        for (raw in values) {
            val display   = MathBidiContract.wrapLtr(raw)
            val committed = MathBidiContract.unwrap(display)
            assertEquals(raw, committed) { "Commit-path round-trip failed for value '$raw'" }
        }
    }
}
