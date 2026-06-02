package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.keyboard.engine.WordPredictor

/**
 * Production [WordPredictor] backed by a [BeamDecoder] and an [EnglishDictionary].
 *
 * Implements both word completion (composing word → top completions) and next-word
 * prediction (composing word empty → top words given the preceding context).
 *
 * Call [predict] from the background executor — it is bounded (beam search over the
 * lexicon) but not instantaneous.  Results are never posted here; the caller is
 * responsible for handing them back to the main thread.
 *
 * [SuggestionSource.query] delegates to [predict] with the default [maxResults].
 *
 * @param decoder       Beam-search decoder; shared with [AutocorrectorImpl].
 * @param freqCache     Per-user frequency table; the same instance owned by the
 *                      [PersonalizationStore] that the IME writes to on each commit.
 *                      The predictor reads [FrequencyCache.logBoost] from it but never
 *                      writes; all policy-gated writes go through [PersonalizationStore].
 * @param geometry      Current key-plane geometry.  May be updated via [updateGeometry]
 *                      when the layout changes.  Null disables spatial scoring.
 */
class WordPredictorImpl internal constructor(
    private val decoder: BeamDecoder,
    private val freqCache: FrequencyCache,
    @Volatile private var geometry: KeyGeometry? = null,
) : WordPredictor {

    /** Replaces the active layout geometry.  Thread-safe (volatile write). */
    fun updateGeometry(geo: KeyGeometry?) {
        geometry = geo
    }

    override fun predict(
        ctx: EditingContext,
        policy: FieldPolicy,
        maxResults: Int,
    ): List<Suggestion> {
        if (!policy.suggestionsEnabled) return emptyList()

        val typed = ctx.composingWord
        val isNextWord = typed.isEmpty()
        val kind  = if (isNextWord) SuggestionKind.NEXT_WORD else SuggestionKind.PREDICTION

        val raw = decoder.decode(typed, ctx, geometry, maxResults)
        return raw.map { (word, score) ->
            Suggestion(kind = kind, text = word, score = score)
        }
    }

    override fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> =
        predict(ctx, policy)

    // Learning writes are handled by [PersonalizationStore.record], not here.
    // The [freqCache] held by this class is the same instance the store writes to,
    // so boost lookups in [BeamDecoder] stay warm without an additional reference.
}
