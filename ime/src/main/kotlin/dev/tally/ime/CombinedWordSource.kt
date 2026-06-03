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
 * @param spell       Optional spell-check source (Stage 5): for a misspelled composing word it
 *                    contributes dictionary corrections so a red-underlined word can be fixed by
 *                    tapping the strip, even when the calibrated autocorrector chose not to fire.
 *                    Null when no dictionary/spell-checker is available yet.
 */
internal class CombinedWordSource(
    private val autocorrect: SuggestionSource,
    private val prediction: SuggestionSource,
    private val spell: SuggestionSource? = null,
) : SuggestionSource {

    override fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> {
        // All sources already honour policy.suggestionsEnabled internally and return empty lists
        // rather than throwing, so no extra gating is needed here.
        val autoCorrections = autocorrect.query(ctx, policy)
        val spellCorrections = spell?.query(ctx, policy) ?: emptyList()
        val predictions = prediction.query(ctx, policy)

        // Merge the calibrated autocorrector candidate (if any) with the spell-check corrections,
        // de-duplicating case-insensitively so the same fix never appears twice. The autocorrector's
        // entry is kept ahead of spell entries because only it carries a confidence used elsewhere.
        val corrections = ArrayList<Suggestion>(autoCorrections.size + spellCorrections.size)
        val correctionTexts = HashSet<String>()
        for (s in autoCorrections) if (correctionTexts.add(s.text.lowercase())) corrections += s
        for (s in spellCorrections) if (correctionTexts.add(s.text.lowercase())) corrections += s

        if (corrections.isEmpty()) return predictions

        // Drop any prediction that duplicates a correction so the strip does not show the same word
        // twice (the correction entry already represents it).
        val dedupedPredictions = predictions.filter { it.text.lowercase() !in correctionTexts }
        return corrections + dedupedPredictions
    }
}
