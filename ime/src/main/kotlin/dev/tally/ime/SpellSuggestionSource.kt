package dev.tally.ime

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.keyboard.engine.SuggestionSource
import dev.tally.prediction.SpellChecker

/**
 * A [SuggestionSource] that surfaces dictionary corrections for a misspelled composing word so a
 * red-underlined word can be fixed by tapping a candidate in the strip (Stage 5 tap-to-correct).
 *
 * The strip's word-tap path ([TallyInputMethodService.commitWordCandidate]) already replaces the
 * composing region with the tapped candidate, so routing spell corrections through the strip reuses
 * that tested commit-and-replace flow rather than inventing a second one. Candidates are emitted with
 * [SuggestionKind.AUTOCORRECT] so the coordinator ranks the best correction first (and styles it),
 * matching the user's expectation that the leading suggestion is the fix.
 *
 * Gated on [enabled] (the spell-check preference) and on the word actually being out-of-dictionary —
 * a correctly-spelled composing word yields no spell candidates here, leaving the strip to ordinary
 * prediction. All work is on-device: the only data source is the bundled dictionary behind
 * [SpellChecker].
 *
 * @param spellChecker On-device checker over the bundled lexicon.
 * @param enabled      Lambda reading the live spell-check preference so a settings toggle takes
 *                     effect without rebuilding the source.
 */
internal class SpellSuggestionSource(
    private val spellChecker: SpellChecker,
    private val enabled: () -> Boolean,
) : SuggestionSource {

    override fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> {
        if (!enabled() || !policy.suggestionsEnabled) return emptyList()
        val word = ctx.composingWord
        if (!spellChecker.isMisspelled(word)) return emptyList()

        val corrections = spellChecker.corrections(word)
        if (corrections.isEmpty()) return emptyList()

        // Score descending by position so the decoder's own ranking is preserved after the
        // coordinator re-sorts by score within the AUTOCORRECT kind.
        return corrections.mapIndexed { idx, text ->
            Suggestion(
                kind  = SuggestionKind.AUTOCORRECT,
                text  = text,
                score = (corrections.size - idx).toFloat(),
                // No calibrated confidence: these are tap-to-correct offers, never silently applied
                // on Space. Leaving confidence null keeps the Space-autocorrect gate from firing on
                // a mere spell suggestion (only AutocorrectorImpl produces a calibrated confidence).
                confidence = null,
            )
        }
    }
}
