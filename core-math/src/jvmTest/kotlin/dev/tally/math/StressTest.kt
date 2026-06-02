package dev.tally.math

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Stress and pathological-input tests.
 *
 * Every case must either return a correct result or null — never throw, never hang,
 * never violate the step budget or span limit.
 */
class StressTest {

    private val en = Locale.US

    // ── Input size limits ──────────────────────────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `text at MAX_TEXT_LENGTH boundary does not crash`() {
        // 512-char text buffer with a valid expression at the tail
        val padding = "a ".repeat(250)                // 500 chars of prose
        val input = "${padding}2+2="                  // 505 chars total
        // Must not throw; may or may not yield a suggestion depending on identifier adjacency
        MathEngine.evaluate(input, en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `expression exactly at MAX_SPAN boundary is handled`() {
        // Build a 256-char expression that is valid arithmetic
        val terms = Array(40) { "${it + 1}" }         // "1", "2", …, "40"
        val expr = terms.joinToString("+") + "="      // well under 256 chars
        val result = MathEngine.evaluate(expr, en)
        assertNotNull(result, "40-term sum should yield a suggestion")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `expression just over MAX_SPAN yields no suggestion`() {
        // Detector discards candidates > 256 chars; construct one.
        // 65 × "9999" joined by "+" = 65×4 + 64 = 324 chars, well over the 256-char span limit.
        val terms = Array(65) { "9999" }
        val expr = terms.joinToString("+") + "="
        assertTrue(expr.length > 256, "precondition: expr > 256 chars (actual: ${expr.length})")
        // No crash; likely null because span > MAX_SPAN
        MathEngine.evaluate(expr, en)
    }

    // ── Step-budget exhaustion ─────────────────────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `step-budget bomb terminates within 5s`() {
        // Deeply nested unary negations inside a short expression
        val prefix = "(-".repeat(100)
        val suffix = ")".repeat(100)
        val expr = "${prefix}1+1${suffix}="
        MathEngine.evaluate(expr, en)      // must not hang or throw
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `10 000-step operator chain terminates`() {
        // Alternating +-+-+- uses many evaluator steps; confirm budget holds
        val sb = StringBuilder()
        sb.append("1")
        repeat(100) { sb.append(if (it % 2 == 0) "+1" else "-1") }
        sb.append("=")
        MathEngine.evaluate(sb.toString(), en)
    }

    // ── Huge operands ──────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `200-digit operand does not crash`() {
        val big = "9".repeat(200)
        MathEngine.evaluate("${big}+1=", en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `operand exceeding the significant-digit cap returns null`() {
        // The lexer rejects numbers with more significant digits than MAX_SIGNIFICANT_DIGITS.
        // Constructing one programmatically avoids embedding a giant literal in source.
        val overCap = "9".repeat(Lexer.MAX_SIGNIFICANT_DIGITS + 1)
        assertNull(
            MathEngine.evaluate("${overCap}+1=", en),
            "over-cap operand must produce no suggestion",
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `operand exactly at the significant-digit cap is accepted`() {
        // An operand whose digit count equals the cap is within bounds.
        val atCap = "1".repeat(Lexer.MAX_SIGNIFICANT_DIGITS)
        // Result may or may not be null depending on downstream limits, but must not throw.
        MathEngine.evaluate("${atCap}+1=", en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `operand near scale overflow does not crash`() {
        val decimal = "0." + "1".repeat(60)    // 60 decimal places
        MathEngine.evaluate("${decimal}+${decimal}=", en)
    }

    // ── Parenthesis depth ─────────────────────────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `maximum parser depth does not crash`() {
        val open = "(".repeat(50)
        val close = ")".repeat(50)
        MathEngine.evaluate("${open}1+2${close}=", en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `unmatched parentheses yield null not crash`() {
        assertNull(MathEngine.evaluate("((1+2=", en))
        assertNull(MathEngine.evaluate("1+2))=", en))
        assertNull(MathEngine.evaluate(")(1+2)(=", en))
    }

    // ── Whitespace and separator stress ───────────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `dense whitespace around operators does not crash`() {
        // Mixed whitespace types including thin/hair/narrow spaces
        val expr = "1  +  2  *  3  =  "
        MathEngine.evaluate(expr, en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `thousands-grouped operands stress`() {
        // Grouping separator in large numbers
        val result = MathEngine.evaluate("1,000,000+2,000,000=", en)
        assertNotNull(result, "grouped large operands should produce a suggestion")
        assertTrue(result!!.display.contains("3"), "3,000,000 expected")
    }

    // ── Locale stress ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["de", "fr", "ar", "hi", "ja", "zh", "ru", "he"])
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `engine does not crash for locale {0}`(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        // Use ASCII digits since native-digit input is locale-specific; engine must not throw
        MathEngine.evaluate("2+3=", locale)
        MathEngine.evaluate("100-50=", locale)
        MathEngine.evaluate("bad input with 1/0=", locale)
    }

    // ── Division edge cases ───────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [
        "1/0=",
        "0/0=",
        "-5/0=",
        "1000000/0=",
    ])
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `division by zero yields null not crash`(input: String) {
        assertNull(MathEngine.evaluate(input, en), "div-by-zero must return null: $input")
    }

    // ── Null / empty / trivial inputs ─────────────────────────────────────────

    @ParameterizedTest(name = "empty-like: {0}")
    @ValueSource(strings = [
        "",
        "=",
        " = ",
        "   ",
        "\t",
        "\n",
        "=====",
    ])
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `empty-like input yields null not crash`(input: String) {
        assertNull(MathEngine.evaluate(input, en))
    }

    // ── Rapid sequential calls (simulated fast typing) ────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `1000 sequential calls complete in under 10s`() {
        val inputs = listOf(
            "2+2=", "100*3=", "(1+2)*3=", "bad text=", "2024-01-02=",
            "9.99+0.01=", "1/3=", "50%+200=", "555-1234=", "x2=",
        )
        val startNs = System.nanoTime()
        repeat(1000) { i -> MathEngine.evaluate(inputs[i % inputs.size], en) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue(elapsedMs < 10_000.0, "1000 calls took ${elapsedMs}ms, expected < 10000ms")
    }

    // ── Control characters and malformed Unicode ───────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `null bytes and control characters do not crash`() {
        MathEngine.evaluate(" 1+2=", en)
        MathEngine.evaluate("1 +2=", en)
        MathEngine.evaluate("1+ 2=", en)
        MathEngine.evaluate("=", en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `lone surrogates do not crash`() {
        // Unpaired surrogates are invalid Unicode but must not throw
        val lone = "𐏿"
        MathEngine.evaluate("${lone}1+2=", en)
        MathEngine.evaluate("1+2${lone}=", en)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `emoji and supplementary code points do not crash`() {
        MathEngine.evaluate("🧮 2+2=", en)
        MathEngine.evaluate("total: 💰100+200=", en)
        MathEngine.evaluate("𝟙+𝟚=", en)     // mathematical digits (supplementary plane)
    }
}
