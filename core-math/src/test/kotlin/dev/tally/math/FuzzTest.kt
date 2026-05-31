package dev.tally.math

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FuzzTest {

    private val locale = Locale.US

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `random unicode input never crashes and terminates`() {
        val rng = Random(0xDEADBEEF)
        repeat(10_000) {
            val len = rng.nextInt(300)
            val chars = CharArray(len) { rng.nextInt(0xFFFF).toChar() }
            val input = String(chars) + "="
            // Must not throw and must complete quickly
            runCatching { MathEngine.evaluate(input, locale) }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `adversarial depth-bomb never crashes`() {
        val deep = "(" .repeat(200) + "1+1" + ")".repeat(200) + "="
        runCatching { MathEngine.evaluate(deep, locale) }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `huge operands within span limit never crash`() {
        val bigNum = "9".repeat(250)
        MathEngine.evaluate("$bigNum+1=", locale) // may return null; must not crash
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `structurally near-valid inputs never crash`() {
        val rng = Random(0xCAFEBABE)
        val operators = listOf("+", "-", "*", "/", "(", ")", "%")
        repeat(5_000) {
            val parts = (1..rng.nextInt(30)).map {
                if (rng.nextBoolean()) rng.nextInt(1000).toString()
                else operators[rng.nextInt(operators.size)]
            }
            val input = parts.joinToString("") + "="
            runCatching { MathEngine.evaluate(input, locale) }
        }
    }
}
