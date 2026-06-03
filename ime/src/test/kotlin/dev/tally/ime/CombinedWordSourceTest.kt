package dev.tally.ime

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.keyboard.engine.SuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CombinedWordSource].
 *
 * Verifies the fan-out that lets autocorrect candidates reach the strip alongside predictions —
 * the wiring that the original service was missing (only the predictor was queried, so AUTOCORRECT
 * candidates, and therefore autocorrect-on-space, never had any input).
 */
class CombinedWordSourceTest {

    private val ctx = EditingContext("teh", "", null)
    private val policy = FieldPolicy.PERMISSIVE

    private fun source(vararg suggestions: Suggestion): SuggestionSource =
        SuggestionSource { _, _ -> suggestions.toList() }

    @Test
    fun mergesAutocorrectAndPredictions() {
        val combined = CombinedWordSource(
            autocorrect = source(Suggestion(SuggestionKind.AUTOCORRECT, "the", 1f, confidence = 0.9f)),
            prediction  = source(Suggestion(SuggestionKind.PREDICTION, "they", 0.5f)),
        )

        val result = combined.query(ctx, policy)

        assertTrue("Correction must be present", result.any { it.kind == SuggestionKind.AUTOCORRECT && it.text == "the" })
        assertTrue("Prediction must be present", result.any { it.kind == SuggestionKind.PREDICTION && it.text == "they" })
    }

    @Test
    fun dropsPredictionDuplicatingCorrection() {
        val combined = CombinedWordSource(
            autocorrect = source(Suggestion(SuggestionKind.AUTOCORRECT, "the", 1f, confidence = 0.9f)),
            prediction  = source(
                Suggestion(SuggestionKind.PREDICTION, "The", 0.6f),   // same word, different case
                Suggestion(SuggestionKind.PREDICTION, "they", 0.5f),
            ),
        )

        val result = combined.query(ctx, policy)

        // "the" must appear exactly once — as the AUTOCORRECT entry that carries the confidence.
        assertEquals(1, result.count { it.text.equals("the", ignoreCase = true) })
        assertEquals(SuggestionKind.AUTOCORRECT, result.first { it.text.equals("the", ignoreCase = true) }.kind)
    }

    @Test
    fun noCorrection_returnsPredictionsUnchanged() {
        val predictions = listOf(
            Suggestion(SuggestionKind.PREDICTION, "the", 0.6f),
            Suggestion(SuggestionKind.PREDICTION, "they", 0.5f),
        )
        val combined = CombinedWordSource(
            autocorrect = source(),               // no correction
            prediction  = source(*predictions.toTypedArray()),
        )

        assertEquals(predictions, combined.query(ctx, policy))
    }
}
