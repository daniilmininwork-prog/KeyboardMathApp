package dev.tally.mathtext

import dev.tally.math.PercentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the pure [ExpressionEvaluator] bridge.
 *
 * These run on the plain JVM (no Android, no Robolectric): the helper is deliberately Android-free
 * so the evaluate-and-format contract can be verified directly. All arithmetic is performed by the
 * real [dev.tally.math.MathEngine] via the helper — nothing here re-implements the evaluator.
 *
 * A fixed Locale.US is passed everywhere so the expected separator characters ('.' decimal,
 * ',' grouping) are deterministic regardless of the machine's default locale.
 */
class ExpressionEvaluatorTest {

    private fun eval(input: String?): String? =
        ExpressionEvaluator.evaluateToDisplayString(input, locale = Locale.US)

    // ── Valid expressions resolve to a formatted result ───────────────────────

    @Test
    fun simpleAddition_returnsResult() {
        assertEquals("4", eval("2+2"))
    }

    @Test
    fun trailingEquals_isNotDoubled() {
        // Input already ends with '='; the helper must not turn "2+2=" into "2+2==".
        assertEquals("4", eval("2+2="))
    }

    @Test
    fun division_usesLocaleGrouping() {
        // Golden case from core-math: 1000000 / 8 = 125,000 with en-US grouping.
        assertEquals("125,000", eval("1000000/8"))
    }

    @Test
    fun decimalArithmetic_returnsResult() {
        assertEquals("0.3", eval("0.1+0.2"))
    }

    @Test
    fun additivePercent_matchesAppleSemantics() {
        // ADDITIVE (default): 100 + 20% = 120.
        assertEquals("120", eval("100+20%"))
    }

    @Test
    fun directPercentMode_isHonoured() {
        // DIRECT: 100 + 20% = 100.2. Confirms the percentMode parameter is wired through.
        assertEquals(
            "100.2",
            ExpressionEvaluator.evaluateToDisplayString(
                "100+20%",
                locale = Locale.US,
                percentMode = PercentMode.DIRECT,
            ),
        )
    }

    // ── Whitespace handling ───────────────────────────────────────────────────

    @Test
    fun surroundingWhitespace_isTrimmed() {
        // A real toolbar selection often carries leading/trailing spaces/newlines.
        assertEquals("4", eval("  2 + 2  "))
        assertEquals("4", eval("\n2+2\t"))
    }

    @Test
    fun whitespaceOnly_returnsNull() {
        assertNull(eval("   "))
        assertNull(eval("\t\n "))
    }

    // ── Invalid / non-expression input degrades gracefully (null, no throw) ────

    @Test
    fun nullInput_returnsNull() {
        assertNull(eval(null))
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull(eval(""))
    }

    @Test
    fun proseText_returnsNull() {
        assertNull(eval("hello world"))
        assertNull(eval("call me at 5"))
    }

    @Test
    fun bareNumber_returnsNull() {
        // A lone number is not a computation (no binary operator) — nothing to replace.
        assertNull(eval("42"))
    }

    @Test
    fun divisionByZero_returnsNullNotCrash() {
        // The engine catches ArithmeticException and returns null; the helper must not throw.
        assertNull(eval("1/0"))
    }

    @Test
    fun garbageOperators_returnNull() {
        assertNull(eval("++**//"))
        assertNull(eval("2+"))
    }
}
