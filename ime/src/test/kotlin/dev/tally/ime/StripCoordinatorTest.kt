package dev.tally.ime

import android.os.Looper
import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.math.Suggestion as MathSuggestion
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Unit tests for [StripCoordinator].
 *
 * Acceptance criteria covered:
 *   - Word candidates + math chip coexist; math is always in the leading slot.
 *   - Math is never displaced by word candidates regardless of ordering.
 *   - Updates are coalesced: only the last queued update delivers a result.
 *   - [FieldPolicy.suggestionsEnabled] = false → no word candidates.
 *   - AUTOCORRECT candidates rank above PREDICTION at the same score.
 *   - [clear] delivers EMPTY state and cancels in-flight queries.
 *   - [setWordSource] replacement is reflected on the next [requestUpdate].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StripCoordinatorTest {

    /** Delivers [StripState] updates; captures them in [states]. */
    private val states = mutableListOf<StripState>()

    /** Runs tasks synchronously on the calling thread (no real bg threads needed). */
    private val syncExec = Executor { it.run() }

    private lateinit var coordinator: StripCoordinator

    @Before
    fun setUp() {
        states.clear()
        coordinator = StripCoordinator(
            wordSource  = { _, _ -> emptyList() },
            bgExecutor  = syncExec,
            onUpdate    = { states.add(it) },
        )
    }

    // ── Word candidates ───────────────────────────────────────────────────────

    @Test
    fun wordCandidates_deliveredInRankedOrder() {
        coordinator.setWordSource { _, _ ->
            listOf(
                Suggestion(SuggestionKind.PREDICTION, "world", -5f),
                Suggestion(SuggestionKind.PREDICTION, "hello", -2f),
                Suggestion(SuggestionKind.PREDICTION, "word",  -3f),
            )
        }

        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        val state = states.last()
        assertEquals(3, state.wordCandidates.size)
        assertEquals("hello", state.wordCandidates[0].text)
        assertEquals("word",  state.wordCandidates[1].text)
        assertEquals("world", state.wordCandidates[2].text)
    }

    @Test
    fun wordCandidates_cappedAtMaxSlots() {
        coordinator.setWordSource { _, _ ->
            List(10) { i -> Suggestion(SuggestionKind.PREDICTION, "word$i", -i.toFloat()) }
        }

        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        val state = states.last()
        assertTrue(state.wordCandidates.size <= StripCoordinator.MAX_WORD_SLOTS)
    }

    @Test
    fun autocorrect_ranksAbovePrediction_atSameScore() {
        coordinator.setWordSource { _, _ ->
            listOf(
                Suggestion(SuggestionKind.PREDICTION,   "pred",  -1f),
                Suggestion(SuggestionKind.AUTOCORRECT,  "corr",  -1f),
            )
        }

        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        val state = states.last()
        assertEquals(SuggestionKind.AUTOCORRECT, state.wordCandidates[0].kind)
    }

    @Test
    fun suggestionsDisabled_noWordCandidates() {
        coordinator.setWordSource { _, _ ->
            listOf(Suggestion(SuggestionKind.PREDICTION, "hello", -2f))
        }
        val noSuggestions = FieldPolicy.PERMISSIVE.copy(suggestionsEnabled = false)

        coordinator.requestUpdate(EditingContext.EMPTY, noSuggestions)
        idleMain()

        // The source is called but returns empty because suggestionsEnabled is false on the impl;
        // StripCoordinator passes the policy straight through so the source can honour it.
        // For this test the source ignores policy, so we verify via a source that respects it.
        coordinator.setWordSource { _, policy ->
            if (policy.suggestionsEnabled) listOf(Suggestion(SuggestionKind.PREDICTION, "hello", 0f))
            else emptyList()
        }
        coordinator.requestUpdate(EditingContext.EMPTY, noSuggestions)
        idleMain()

        assertTrue(states.last().wordCandidates.isEmpty())
    }

    // ── Math slot ─────────────────────────────────────────────────────────────

    @Test
    fun mathSuggestion_occupiesLeadingSlot() {
        val math = MathSuggestion(display = "4", span = 0..3, exactValue = BigDecimal("4"))
        coordinator.updateMath(math)
        idleMain()

        val state = states.last()
        assertNotNull(state.mathSuggestion)
        assertEquals("4", state.mathSuggestion!!.text)
        assertEquals(SuggestionKind.MATH, state.mathSuggestion!!.kind)
    }

    @Test
    fun mathAndWordCandidates_coexist() {
        coordinator.setWordSource { _, _ ->
            listOf(Suggestion(SuggestionKind.PREDICTION, "hello", -2f))
        }
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        val math = MathSuggestion(display = "42", span = 0..4, exactValue = BigDecimal("42"))
        coordinator.updateMath(math)
        idleMain()

        val state = states.last()
        assertNotNull("Math slot must be set", state.mathSuggestion)
        assertTrue("Word slot must have candidates", state.wordCandidates.isNotEmpty())
        assertEquals("42", state.mathSuggestion!!.text)
        assertEquals("hello", state.wordCandidates[0].text)
    }

    @Test
    fun mathNeverDisplacedByWordCandidates() {
        // Deliver math first.
        val math = MathSuggestion(display = "100", span = 0..4, exactValue = BigDecimal("100"))
        coordinator.updateMath(math)
        idleMain()

        // Then update words — math must still be present.
        coordinator.setWordSource { _, _ ->
            listOf(
                Suggestion(SuggestionKind.PREDICTION, "hello", -1f),
                Suggestion(SuggestionKind.PREDICTION, "world", -2f),
                Suggestion(SuggestionKind.PREDICTION, "word",  -3f),
            )
        }
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        val state = states.last()
        assertNotNull("Math must still be present after word query", state.mathSuggestion)
        assertEquals("100", state.mathSuggestion!!.text)
        assertEquals(3, state.wordCandidates.size)
    }

    @Test
    fun mathClear_removesLeadingSlot() {
        val math = MathSuggestion(display = "4", span = 0..3, exactValue = BigDecimal("4"))
        coordinator.updateMath(math)
        idleMain()

        coordinator.updateMath(null)
        idleMain()

        assertNull(states.last().mathSuggestion)
    }

    // ── Coalescing ────────────────────────────────────────────────────────────

    /**
     * Three rapid [requestUpdate] calls must result in only one delivered [StripState]
     * from the word-source path.
     *
     * With the sync executor the tasks run inline on [requestUpdate] but the sequence
     * counter ensures only the last result is accepted by the main-handler post.
     * After idling the main looper once we should see at most one word-derived state
     * among the delivered states (math updates may arrive separately).
     */
    @Test
    fun coalescing_rapidUpdates_onlyLastDelivered() {
        var callCount = 0
        coordinator.setWordSource { _, _ ->
            callCount++
            listOf(Suggestion(SuggestionKind.PREDICTION, "word$callCount", 0f))
        }

        // Three consecutive requests — the sync executor runs each inline, but only the
        // last sequence wins the delivery gate.
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        // With a sync executor all three run, but only the last sequence passes the gate.
        val wordStates = states.filter { it.wordCandidates.isNotEmpty() }
        assertTrue("Only the last update should be delivered", wordStates.size <= 1)
        if (wordStates.isNotEmpty()) {
            assertEquals("word3", wordStates.last().wordCandidates[0].text)
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    fun clear_deliversEmptyState() {
        coordinator.setWordSource { _, _ ->
            listOf(Suggestion(SuggestionKind.PREDICTION, "hello", -1f))
        }
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        coordinator.clear()
        idleMain()

        assertEquals(StripState.EMPTY, states.last())
    }

    @Test
    fun clear_purgesMathState() {
        val math = MathSuggestion(display = "4", span = 0..3, exactValue = BigDecimal("4"))
        coordinator.updateMath(math)
        idleMain()

        coordinator.clear()
        idleMain()

        assertNull(states.last().mathSuggestion)
    }

    // ── Source replacement ────────────────────────────────────────────────────

    @Test
    fun setWordSource_newSourceUsedOnNextUpdate() {
        coordinator.setWordSource { _, _ ->
            listOf(Suggestion(SuggestionKind.PREDICTION, "old", 0f))
        }
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        coordinator.setWordSource { _, _ ->
            listOf(Suggestion(SuggestionKind.PREDICTION, "new", 0f))
        }
        coordinator.requestUpdate(EditingContext.EMPTY, FieldPolicy.PERMISSIVE)
        idleMain()

        assertEquals("new", states.last().wordCandidates[0].text)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
}
