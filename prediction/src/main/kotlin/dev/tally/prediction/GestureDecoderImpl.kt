package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.GestureDecoder
import dev.tally.keyboard.engine.GlidePath
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind

/**
 * Statistical gesture-typing decoder.
 *
 * Resamples the user's swipe trace, then scores every word in the lexicon against the
 * ideal key-path for that word, finally reranks using the n-gram language model.
 *
 * **Algorithm summary (FlorisBoard Apache-2.0 lineage):**
 * 1. Resample the raw trace to [PathResampler.targetCount] equally-spaced points.
 * 2. For each candidate word in the lexicon, build its ideal key-center path via
 *    [IdealPathBuilder], resample it to the same count, then compute the mean
 *    squared displacement score in [GesturePathScorer].
 * 3. Combine gesture score and LM log-prob: `α × gestureScore + (1−α) × lmScore`.
 * 4. Keep the top [beamWidth] candidates during the scan; return the top [maxResults]
 *    after the full lexicon pass.
 *
 * The call is bounded (single lexicon pass) and cancellable via [isActive].
 *
 * @param dictionary   Shared lexicon + language model.
 * @param resampler    Path resampler; default 32 points.
 * @param scorer       Per-word gesture scorer.
 * @param alpha        Weight of gesture score vs LM score (0 = pure LM, 1 = pure gesture).
 * @param beamWidth    Candidate beam kept during the lexicon scan.
 */
class GestureDecoderImpl internal constructor(
    private val dictionary: EnglishDictionary,
    private val resampler: PathResampler = PathResampler(),
    private val scorer: GesturePathScorer = GesturePathScorer(),
    private val alpha: Float = 0.7f,
    private val beamWidth: Int = 8,
) : GestureDecoder {

    override fun decode(
        path: GlidePath,
        geometry: KeyGeometry,
        context: EditingContext,
        policy: FieldPolicy,
        maxResults: Int,
        isActive: () -> Boolean,
    ): List<Suggestion> {
        if (!policy.glideEnabled) return emptyList()
        if (path.points.size < 2) return emptyList()
        if (dictionary.wordCount == 0) return emptyList()

        val tracePts = resampler.resample(path.points)
        val avgKeyWidth = if (geometry.keys.isEmpty()) 50f
                         else geometry.keys.map { it.width }.average().toFloat()
        val sigma = (geometry.rowHeight * 0.4f).coerceAtLeast(1f)
        val adaptiveScorer = GesturePathScorer(
            resampler = resampler,
            sigma = sigma,
            endpointPruneThreshold = 2.5f,
        )

        val prevWord = context.wordBeforeCursor
        val candidates = mutableListOf<Pair<String, Float>>()

        for (i in 0 until dictionary.wordCount) {
            if (!isActive()) break

            val word = dictionary.words[i]
            // Single-char words can't form a meaningful glide path.
            if (word.length < 2) continue

            val gestureScore = adaptiveScorer.score(word, tracePts, geometry, avgKeyWidth)
            if (gestureScore == Float.NEGATIVE_INFINITY) continue

            val lmScore = if (prevWord != null) {
                dictionary.bigramLogProb(prevWord, word)
            } else {
                dictionary.unigramLogProb(word)
            }

            val combined = alpha * gestureScore + (1f - alpha) * lmScore
            candidates += Pair(word, combined)

            // Prune the beam periodically to avoid O(N²) sorting.
            if (candidates.size > beamWidth * 4) {
                candidates.sortByDescending { it.second }
                candidates.subList(beamWidth * 2, candidates.size).clear()
            }
        }

        candidates.sortByDescending { it.second }
        return candidates.take(maxResults).map { (word, score) ->
            Suggestion(kind = SuggestionKind.PREDICTION, text = word, score = score)
        }
    }
}
