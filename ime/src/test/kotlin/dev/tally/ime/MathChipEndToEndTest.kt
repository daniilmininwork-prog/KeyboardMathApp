package dev.tally.ime

import android.os.Looper
import dev.tally.glue.MathEvaluator
import dev.tally.math.Suggestion as MathSuggestion
import java.math.BigDecimal
import java.util.concurrent.Executor
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

/**
 * End-to-end tests for the math chip pipeline (T2.4).
 *
 * Exercises the chain: [MathEvaluator] → [MathSuggestionSource.onResult] →
 * [StripCoordinator.updateMath] → strip state.
 *
 * Acceptance criteria:
 *   - Math chip surfaces when text ends with `=` and contains a valid expression.
 *   - Veto cases (date formats, version strings, plain text) produce no chip.
 *   - The [MathSuggestionSource] holds span and exactValue for subsequent commit.
 *   - Committing via [MathSuggestionSource] clears the result (no stale re-insert).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MathChipEndToEndTest {

    private val syncExec = Executor { it.run() }

    /**
     * Drives the evaluator → mathSource chain synchronously and returns the final
     * [StripState] from the coordinator.
     */
    private fun evaluate(text: String): Pair<MathSuggestionSource, StripState?> {
        val mathSource = MathSuggestionSource()
        val states = mutableListOf<StripState>()
        val coordinator = StripCoordinator(
            wordSource = { _, _ -> emptyList() },
            bgExecutor = syncExec,
            onUpdate   = { states.add(it) },
        )

        var lastMathResult: MathSuggestion? = null
        val evaluator = MathEvaluator(debounceMs = 0L, bgExecutor = syncExec) { math ->
            lastMathResult = math
            mathSource.onResult(math)
            coordinator.updateMath(math)
        }

        evaluator.onTextChanged(text)
        // First idle: fires the debounce runnable (runs the executor + posts result callback).
        shadowOf(Looper.getMainLooper()).idle()
        // Second idle: delivers the result callback.
        shadowOf(Looper.getMainLooper()).idle()

        return mathSource to states.lastOrNull()
    }

    // ── Math chip surfaces on = ───────────────────────────────────────────────

    @Test
    fun addition_chipSurfaces() {
        val (source, state) = evaluate("2+2=")

        assertNotNull("Strip must have a math suggestion", state?.mathSuggestion)
        assertEquals("4", state!!.mathSuggestion!!.text)
        assertTrue("Source must hold the result", source.hasResult)
    }

    @Test
    fun multiplication_chipSurfaces() {
        val (source, state) = evaluate("6*7=")

        assertNotNull(state?.mathSuggestion)
        assertEquals("42", state!!.mathSuggestion!!.text)
        assertTrue(source.hasResult)
    }

    @Test
    fun complexExpression_chipSurfaces() {
        val (source, state) = evaluate("10+5*2=")

        assertNotNull(state?.mathSuggestion)
        assertTrue(source.hasResult)
    }

    @Test
    fun chipHoldsSpanForCommit() {
        val (source, _) = evaluate("2+2=")

        assertTrue(source.hasResult)
        val math = source.latest
        assertNotNull("latest must be non-null when hasResult is true", math)
        // The span starts at or before the expression
        assertTrue("span.first must be ≥ 0", math!!.span.first >= 0)
    }

    @Test
    fun chipHoldsExactValueForCommit() {
        val (source, _) = evaluate("10*4=")

        assertTrue(source.hasResult)
        val math = source.latest
        assertNotNull(math)
        // exactValue carries the full-precision BigDecimal — here 10*4 = 40 exactly
        assertEquals(BigDecimal("40"), math!!.exactValue)
    }

    // ── Veto cases show nothing ───────────────────────────────────────────────

    @Test
    fun plainText_noChip() {
        val (source, state) = evaluate("hello world")

        assertNull("Plain text must not produce a chip", state?.mathSuggestion)
        assertFalse(source.hasResult)
    }

    @Test
    fun expressionWithoutEquals_noChip() {
        val (source, state) = evaluate("2+2")

        assertNull(state?.mathSuggestion)
        assertFalse(source.hasResult)
    }

    @Test
    fun datePattern_vetoed_noChip() {
        val (source, state) = evaluate("12/25/2024")

        assertNull("Date pattern must be vetoed", state?.mathSuggestion)
        assertFalse(source.hasResult)
    }

    @Test
    fun versionString_vetoed_noChip() {
        val (source, state) = evaluate("v1.2.3")

        assertNull("Version string must be vetoed", state?.mathSuggestion)
        assertFalse(source.hasResult)
    }

    @Test
    fun emptyText_noChip() {
        val (source, state) = evaluate("")

        assertNull(state?.mathSuggestion)
        assertFalse(source.hasResult)
    }

    // ── Commit lifecycle ──────────────────────────────────────────────────────

    @Test
    fun commit_clearsResult_noStaleReInsert() {
        val (source, _) = evaluate("3+3=")
        assertTrue(source.hasResult)

        var commitCount = 0
        source.commit { commitCount++ }
        source.commit { commitCount++ }

        assertEquals("Only the first commit must fire", 1, commitCount)
        assertFalse(source.hasResult)
    }

    @Test
    fun clear_removesChipFromStrip() {
        val mathSource = MathSuggestionSource()
        val states = mutableListOf<StripState>()
        val coordinator = StripCoordinator(
            wordSource = { _, _ -> emptyList() },
            bgExecutor = syncExec,
            onUpdate   = { states.add(it) },
        )

        coordinator.updateMath(MathSuggestion("4", 0..3, BigDecimal("4")))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(states.last().mathSuggestion)

        mathSource.clear()
        coordinator.updateMath(null)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("Clearing must remove chip from strip", states.last().mathSuggestion)
        assertFalse(mathSource.hasResult)
    }
}
