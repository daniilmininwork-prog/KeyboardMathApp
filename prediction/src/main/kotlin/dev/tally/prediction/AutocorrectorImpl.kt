package dev.tally.prediction

import dev.tally.keyboard.engine.Autocorrector
import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind

/**
 * Production [Autocorrector] driven by the same [BeamDecoder] used for word prediction.
 *
 * Fires a correction suggestion when the highest-scoring candidate for the composing
 * word is distinct from the typed string AND exceeds [CORRECTION_THRESHOLD] relative
 * to the typed word's own score. The threshold is intentionally conservative:
 * a false autocorrect is more disruptive to the user than a missed correction.
 *
 * High-frequency known words (unigram log-prob > [KNOWN_WORD_FLOOR]) are never
 * corrected — if the user typed a real word we leave it alone, even when a similar
 * word scores slightly higher under the spatial model.
 *
 * [SuggestionSource.query] wraps [correct] in a single-element list (or empty list).
 *
 * @param decoder     Beam-search decoder shared with [WordPredictorImpl].
 * @param dictionary  Language model; used to check whether the typed word is known.
 * @param geometry    Current key-plane geometry; may be updated via [updateGeometry].
 */
class AutocorrectorImpl internal constructor(
    private val decoder: BeamDecoder,
    private val dictionary: EnglishDictionary,
    @Volatile private var geometry: KeyGeometry? = null,
) : Autocorrector {

    /** Replaces the active layout geometry. */
    fun updateGeometry(geo: KeyGeometry?) {
        geometry = geo
    }

    override fun correct(ctx: EditingContext, policy: FieldPolicy): Suggestion? {
        if (!policy.suggestionsEnabled) return null

        val typed = ctx.composingWord
        if (typed.length < MIN_WORD_LENGTH) return null

        // Don't correct a word the user clearly knows (already in lexicon at high prob).
        val typedLogProb = dictionary.unigramLogProb(typed.lowercase())
        if (typedLogProb > KNOWN_WORD_FLOOR) return null

        val candidates = decoder.decode(typed, ctx, geometry, maxResults = 2)
        if (candidates.isEmpty()) return null

        val (topWord, topScore) = candidates[0]
        if (topWord.equals(typed, ignoreCase = true)) return null

        // Require the correction to beat the typed word's own score by a margin.
        val typedScore = candidates.firstOrNull { it.first.equals(typed, ignoreCase = true) }?.second
            ?: (topScore - CORRECTION_THRESHOLD - 1f)  // typed word not in beam → correct freely

        return if (topScore - (typedScore) >= CORRECTION_THRESHOLD) {
            Suggestion(kind = SuggestionKind.AUTOCORRECT, text = topWord, score = topScore)
        } else {
            null
        }
    }

    override fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> =
        listOfNotNull(correct(ctx, policy))

    companion object {
        /** Minimum composing-word length before autocorrect will fire. */
        const val MIN_WORD_LENGTH: Int = 3

        /**
         * Unigram log₂-probability floor above which a word is considered "known" and
         * will not be corrected.  Words more probable than this are assumed intentional.
         * ~ log₂(1/200) ≈ −7.6 (a word appearing ~once per 200 words in the corpus).
         */
        const val KNOWN_WORD_FLOOR: Float = -7.6f

        /**
         * Minimum score margin the correction candidate must clear over the typed word
         * to fire.  Calibrated for a conservative error rate.
         */
        const val CORRECTION_THRESHOLD: Float = 3f
    }
}
