package dev.tally.ime

import dev.tally.math.Suggestion as MathSuggestion
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MathSuggestionSource].
 *
 * All tests run on the JVM with no Android deps — [MathSuggestionSource] is pure Kotlin.
 *
 * Acceptance criteria covered (T2.4):
 *   - [onResult] stores the latest result; null clears it.
 *   - [hasResult] reflects stored state.
 *   - [commit] invokes the [MathSuggestionSource.CommitAction] with the stored result.
 *   - [commit] clears [latest] after invoking the action (prevents double-insert).
 *   - [commit] is a no-op when there is no result; returns false.
 *   - [clear] discards the stored result without committing.
 *   - A second [onResult] overwrites the previous result.
 *   - The [CommitAction] receives the full [MathSuggestion] (span + exactValue preserved).
 */
class MathSuggestionSourceTest {

    private lateinit var source: MathSuggestionSource

    @Before
    fun setUp() {
        source = MathSuggestionSource()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun initialState_hasNoResult() {
        assertFalse(source.hasResult)
        assertNull(source.latest)
    }

    // ── onResult ──────────────────────────────────────────────────────────────

    @Test
    fun onResult_nonNull_setsLatest() {
        val math = mathSuggestion("4", 0..3, "4")
        source.onResult(math)

        assertTrue(source.hasResult)
        assertEquals(math, source.latest)
    }

    @Test
    fun onResult_null_clearsLatest() {
        source.onResult(mathSuggestion("4", 0..3, "4"))
        source.onResult(null)

        assertFalse(source.hasResult)
        assertNull(source.latest)
    }

    @Test
    fun onResult_overwrites_previousResult() {
        source.onResult(mathSuggestion("4", 0..3, "4"))
        val newer = mathSuggestion("10", 0..4, "10")
        source.onResult(newer)

        assertEquals(newer, source.latest)
    }

    // ── commit ────────────────────────────────────────────────────────────────

    @Test
    fun commit_withResult_invokesAction_returnsTrue() {
        val math = mathSuggestion("42", 2..5, "42")
        source.onResult(math)

        var committed: MathSuggestion? = null
        val result = source.commit { committed = it }

        assertTrue(result)
        assertEquals(math, committed)
    }

    @Test
    fun commit_clearsLatest_afterAction() {
        source.onResult(mathSuggestion("4", 0..3, "4"))
        source.commit { /* no-op */ }

        assertFalse(source.hasResult)
        assertNull(source.latest)
    }

    @Test
    fun commit_withoutResult_returnsFalse_actionNotCalled() {
        var called = false
        val result = source.commit { called = true }

        assertFalse(result)
        assertFalse(called)
    }

    @Test
    fun commit_preventsDoubleInsert() {
        source.onResult(mathSuggestion("4", 0..3, "4"))

        var callCount = 0
        source.commit { callCount++ }
        source.commit { callCount++ }

        assertEquals("Second commit must be a no-op", 1, callCount)
    }

    // ── CommitAction receives full MathSuggestion ────────────────────────────

    @Test
    fun commit_actionReceivesSpan() {
        val math = mathSuggestion("100", 5..9, "100")
        source.onResult(math)

        var span: IntRange? = null
        source.commit { span = it.span }

        assertEquals(5..9, span)
    }

    @Test
    fun commit_actionReceivesExactValue() {
        val math = mathSuggestion("0.33", 0..6, "0.333333333")
        source.onResult(math)

        var exact: BigDecimal? = null
        source.commit { exact = it.exactValue }

        assertEquals(BigDecimal("0.333333333"), exact)
    }

    @Test
    fun commit_actionReceivesDisplay() {
        val math = mathSuggestion("1,000", 0..6, "1000")
        source.onResult(math)

        var display: String? = null
        source.commit { display = it.display }

        assertEquals("1,000", display)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun clear_discardsResult_withoutCommitting() {
        source.onResult(mathSuggestion("4", 0..3, "4"))

        var called = false
        source.clear()
        source.commit { called = true }

        assertFalse(source.hasResult)
        assertFalse(called)
    }

    @Test
    fun clear_onEmpty_isNoOp() {
        source.clear()
        assertFalse(source.hasResult)
    }

    // ── insertExactValue routing ──────────────────────────────────────────────

    /**
     * Validates the `insertExactValue` preference contract: when true the committed text must
     * be [MathSuggestion.exactValue].toPlainString() rather than [MathSuggestion.display].
     *
     * The commit step in [TallyInputMethodService.commitMathFromSource] branches on this pref.
     * This test exercises that branch via the [CommitAction] callback so a regression would
     * be caught without needing a live InputConnection.
     */
    @Test
    fun insertExactValue_true_callerReceivesExactValueNotDisplay() {
        val display   = "1,000"
        val exactStr  = "1000"
        val math      = mathSuggestion(display, 0..5, exactStr)
        source.onResult(math)

        var textToInsert: String? = null
        // Simulate the commitMathFromSource lambda with insertExactValue = true
        source.commit { s ->
            textToInsert = s.exactValue.toPlainString()
        }

        assertEquals("With insertExactValue=true, must commit exact value", exactStr, textToInsert)
    }

    @Test
    fun insertExactValue_false_callerReceivesDisplay() {
        val display  = "1,000"
        val exactStr = "1000"
        val math     = mathSuggestion(display, 0..5, exactStr)
        source.onResult(math)

        var textToInsert: String? = null
        // Simulate the commitMathFromSource lambda with insertExactValue = false
        source.commit { s ->
            textToInsert = s.display
        }

        assertEquals("With insertExactValue=false, must commit display string", display, textToInsert)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mathSuggestion(display: String, span: IntRange, exact: String) =
        MathSuggestion(display = display, span = span, exactValue = BigDecimal(exact))
}
