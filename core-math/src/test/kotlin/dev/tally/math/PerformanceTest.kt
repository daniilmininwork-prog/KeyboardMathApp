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
}
