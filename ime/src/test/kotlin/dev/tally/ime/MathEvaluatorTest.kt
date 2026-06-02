package dev.tally.ime

import android.os.Looper
import android.text.InputType
import dev.tally.glue.MathEvaluator
import dev.tally.math.PercentMode
import dev.tally.math.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [MathEvaluator] using Robolectric (JVM-runnable, no device required).
 *
 * All tests inject a synchronous [Executor] so the evaluation result is available after
 * idling the main looper — no real threads or timing delays needed.
 *
 * Acceptance criteria covered (Phase 3, §10-implementation-plan.md):
 *   - Golden cases reproduce end-to-end through the evaluator.
 *   - Veto cases produce no suggestion.
 *   - Debounce coalesces rapid calls to a single evaluation.
 *   - [MathEvaluator.cancel] prevents delivery of a pending result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MathEvaluatorTest {

    private val syncExecutor = Executor { it.run() }

    /** Runs [text] through the evaluator with zero debounce and a synchronous executor. */
    private fun evaluateSync(
        text: String,
        percentMode: PercentMode = PercentMode.ADDITIVE,
        precision: Int = dev.tally.glue.TallyPreferences.DEFAULT_PRECISION,
    ): Suggestion? {
        var result: Suggestion? = null
        val evaluator = MathEvaluator(debounceMs = 0L, bgExecutor = syncExecutor) { r ->
            result = r
        }
        evaluator.onTextChanged(text, percentMode = percentMode, precision = precision)
        // Two idles: first processes the debounce runnable (which synchronously runs the
        // executor and posts the result callback); second delivers the result callback.
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()
        return result
    }

    // ── Golden cases (end-to-end through evaluator) ───────────────────────────

    @Test
    fun addition_returnsSuggestion() {
        val s = evaluateSync("2+2=")
        assertNotNull(s)
        assertEquals("4", s!!.display)
    }

    @Test
    fun multiplication_returnsSuggestion() {
        val s = evaluateSync("10*5=")
        assertNotNull(s)
        assertEquals("50", s!!.display)
    }

    @Test
    fun complexExpression_returnsCorrectResult() {
        val s = evaluateSync("10*5+3=")
        assertNotNull(s)
        assertEquals("53", s!!.display)
    }

    @Test
    fun additivePercent_returnsCorrectResult() {
        val s = evaluateSync("200+15%=")
        assertNotNull(s)
        assertEquals("230", s!!.display)
    }

    // ── No-suggestion cases ───────────────────────────────────────────────────

    @Test
    fun plainText_returnsNull() {
        assertNull(evaluateSync("hello world"))
    }

    @Test
    fun emptyText_returnsNull() {
        assertNull(evaluateSync(""))
    }

    @Test
    fun expressionWithoutEquals_returnsNull() {
        assertNull(evaluateSync("2+2"))
    }

    @Test
    fun vetoPattern_dateFormat_returnsNull() {
        assertNull(evaluateSync("12/25/2024"))
    }

    @Test
    fun vetoPattern_versionString_returnsNull() {
        assertNull(evaluateSync("v1.2.3"))
    }

    // ── Debounce ──────────────────────────────────────────────────────────────

    @Test
    fun debounce_rapidCalls_onlyLastEvaluated() {
        val results = mutableListOf<Suggestion?>()
        val evaluator = MathEvaluator(debounceMs = 120L, bgExecutor = syncExecutor) { r ->
            results.add(r)
        }
        evaluator.onTextChanged("1+")
        evaluator.onTextChanged("1+2")
        evaluator.onTextChanged("1+2=")

        // Advance clock past the debounce window so the last-posted runnable fires.
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Only one result must be delivered", 1, results.size)
        assertNotNull(results[0])
        assertEquals("3", results[0]!!.display)
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Test
    fun cancel_preventsDelivery() {
        var delivered = false
        val evaluator = MathEvaluator(debounceMs = 200L, bgExecutor = syncExecutor) {
            delivered = true
        }
        evaluator.onTextChanged("2+2=")
        evaluator.cancel()

        // Advance past the debounce — nothing should fire.
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("Cancelled evaluator must not deliver a result", delivered)
    }

    // ── PercentMode wire ──────────────────────────────────────────────────────

    /**
     * Validates the wire from prefs → MathEvaluator → MathEngine for [PercentMode.DIRECT].
     *
     * In DIRECT mode `100 + 20%` means `100 + (20/100) = 100 + 0.20 = 100.2`.
     * In ADDITIVE mode (the default) the same expression means `100 + (100 × 0.20) = 120`.
     * This test confirms that passing `percentMode = PercentMode.DIRECT` is forwarded all
     * the way through to MathEngine and produces a different result from the default.
     */
    @Test
    fun directPercentMode_forwardsToMathEngine() {
        val s = evaluateSync("100+20%=", percentMode = PercentMode.DIRECT)
        assertNotNull("DIRECT percent mode should produce a result for '100+20%='", s)
        assertEquals(
            "DIRECT: 100 + 20% should equal 100.2 (not 120)",
            "100.2",
            s!!.display,
        )
    }

    /**
     * Validates the wire from prefs → MathEvaluator → MathEngine for the [precision] parameter.
     *
     * With precision=2, 100/3 should display as "33.33" rather than the default "33.333333".
     */
    @Test
    fun precision_forwardsToMathEngine() {
        val s = evaluateSync("100/3=", precision = 2)
        assertNotNull("Precision=2 should produce a result for '100/3='", s)
        assertEquals(
            "With precision=2, 100/3 should display as '33.33'",
            "33.33",
            s!!.display,
        )
    }
}
