package dev.tally.math

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class PerformanceTest {

    private val locale = Locale.US

    private val benchmarkInputs = listOf(
        "47.50/3=",
        "2+3*4=",
        "(100+200)*3/4=",
        "1000000/8=",
        "0.1+0.2=",
        "200+10%=",
        "the answer is 6*7=",
        "2024-01-02=",     // should be vetoed quickly
        "555-1234=",       // should be vetoed quickly
    )

    @Test
    fun `engine runs within 5ms per call on average`() {
        // Warm-up JIT
        repeat(500) { i -> MathEngine.evaluate(benchmarkInputs[i % benchmarkInputs.size], locale) }

        val runs = 2_000
        val startNs = System.nanoTime()
        repeat(runs) { i ->
            MathEngine.evaluate(benchmarkInputs[i % benchmarkInputs.size], locale)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val avgMs = elapsedMs / runs

        assertTrue(avgMs < 5.0, "Average call time ${avgMs}ms exceeds 5ms ceiling")
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 5, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `worst-case 256-char span with many word candidates completes within ceiling`() {
        // Build a 256-char window packed with word-boundary candidates — every pair of
        // characters is a "word" — to exercise the two-dimensional fragment search at
        // maximum fan-out. The expression itself is valid so the lexer can short-circuit
        // on the right match, but the scanner must not blow up trying all candidates.
        // Construct: "aa bb cc … 100+50=" so the arithmetic is right-anchored and the
        // prose words each fail tokenization quickly.
        val sb = StringBuilder()
        while (sb.length < 220) sb.append("ab ")   // prose words, each fails at 'a'
        sb.append("100+50=")
        val worstCase = sb.toString()

        // Warm up to avoid JIT skewing the first call
        repeat(20) { MathEngine.evaluate(worstCase, locale) }

        val runs = 500
        val startNs = System.nanoTime()
        repeat(runs) { MathEngine.evaluate(worstCase, locale) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val avgMs = elapsedMs / runs

        assertTrue(avgMs < 5.0, "Worst-case span avg ${avgMs}ms exceeds 5ms ceiling")
    }
}
