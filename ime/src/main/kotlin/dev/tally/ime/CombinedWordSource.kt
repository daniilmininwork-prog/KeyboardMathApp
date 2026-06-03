package dev.tally.ime

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionSource

/**
 * A [SuggestionSource] that fans a single strip query out to the autocorrector and the word
 * predictor, returning their merged candidates.
 *
 * The [StripCoordinator] queries exactly one word source on its background executor. Wiring the
 * predictor alone (as the service did originally) meant the [dev.tally.prediction.AutocorrectorImpl]
 * — and therefore every AUTOCORRECT candidate, the only kind that carries a calibrated
 * [Suggestion.confidence] — never reached the strip, so autocorrect-on-space had nothing to apply.
 *
 * Composing both here keeps the coordinator's single-source contract intact: the coordinator's
 * own ranking (AUTOCORRECT first, then by score) and slot cap still apply to the combined list.
 *
 * @param autocorrect Queried first so its (usually single) AUTOCORRECT candidate is always present
 *                    in the merged list; the coordinator then surfaces it as the top correction.
 * @param prediction  Queried for ordinary word completions / next-word candidates.
 */
internal class CombinedWordSource(
    private val autocorrect: SuggestionSource,
    private val prediction: SuggestionSource,
) : SuggestionSource {

    override fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> {
        // Both sources already honour policy.suggestionsEnabled internally and return empty lists
        // rather than throwing, so no extra gating is needed here.
        val corrections = autocorrect.query(ctx, policy)
        val predictions = prediction.query(ctx, policy)
        if (corrections.isEmpty()) return predictions

        // Drop any prediction that duplicates the correction text so the strip does not show the
        // same word twice (the AUTOCORRECT entry already represents it, with confidence attached).
        val correctionTexts = corrections.mapTo(HashSet()) { it.text.lowercase() }
        val dedupedPredictions = predictions.filter { it.text.lowercase() !in correctionTexts }
        return corrections + dedupedPredictions
    }
}
