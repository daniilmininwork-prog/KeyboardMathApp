package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.KeyGeometry

/**
 * Beam-search decoder that combines spatial proximity and n-gram language model scores
 * to produce word-completion and autocorrect candidates.
 *
 * The decoder performs a single-pass scan over the lexicon (no trie is required at this
 * scale), scoring each word with:
 *
 *   combined = α × spatialLog + (1 − α) × lmLog
 *
 * where [alpha] controls the balance between keyboard-proximity and language-model
 * evidence.  Candidates are pruned to the top [beamWidth] by combined score.
 *
 * This is the P0-5 decoder described in `04 §2.2`.  Glide (gesture path) decoding is
 * deferred to T4.1 and will plug in behind the [dev.tally.keyboard.engine.GestureDecoder]
 * interface without modifying this class.
 *
 * @param dictionary  In-memory language model loaded by [DictionaryLoader].
 * @param scorer      Spatial proximity scorer.
 * @param freqCache   Per-user frequency table; boosts user-familiar words.
 * @param alpha       Spatial weight in the combined score (0 = pure LM, 1 = pure spatial).
 * @param beamWidth   Maximum candidates to keep during search.
 */
internal class BeamDecoder(
    private val dictionary: EnglishDictionary,
    private val scorer: SpatialScorer = SpatialScorer(),
    private val freqCache: FrequencyCache = FrequencyCache.NOOP,
    private val alpha: Float = 0.6f,
    private val beamWidth: Int = 8,
) {

    /**
     * Returns the top [maxResults] completions for [typed] in the context of [ctx].
     *
     * [geometry] is null when the caller has no layout geometry available (e.g.
     * in unit tests or next-word mode); in that case the scorer falls back to
     * prefix / edit-distance matching.
     *
     * The returned list is sorted by combined score descending; each element pairs
     * (candidate word, combined score).
     */
    fun decode(
        typed: String,
        ctx: EditingContext,
        geometry: KeyGeometry?,
        maxResults: Int,
    ): List<Pair<String, Float>> {
        if (dictionary.wordCount == 0) return emptyList()
        if (typed.isEmpty() && ctx.wordBeforeCursor == null) return emptyList()

        val lowerTyped = typed.lowercase()
        val candidates = mutableListOf<Pair<String, Float>>()

        for (i in 0 until dictionary.wordCount) {
            val word = dictionary.words[i]
            if (!isPrefixCompatible(lowerTyped, word)) continue

            val prevWord = ctx.wordBeforeCursor
            val lmLog = if (prevWord != null) {
                dictionary.bigramLogProb(prevWord, word)
            } else {
                dictionary.unigramLogProb(word)
            }

            val activeGeo = geometry
            val spatialLog = if (activeGeo != null && lowerTyped.isNotEmpty()) {
                scorer.logScore(lowerTyped, word, activeGeo)
            } else if (lowerTyped.isNotEmpty()) {
                editDistancePenalty(lowerTyped, word)
            } else {
                0f  // next-word: no typed string, rely on LM only
            }

            if (spatialLog == Float.NEGATIVE_INFINITY) continue

            val userBoost = freqCache.logBoost(word)
            val combined  = alpha * spatialLog + (1f - alpha) * lmLog + userBoost

            candidates += Pair(word, combined)

            // Keep the beam pruned to avoid O(N²) sorting overhead.
            if (candidates.size > beamWidth * 4) {
                candidates.sortByDescending { it.second }
                candidates.subList(beamWidth * 2, candidates.size).clear()
            }
        }

        candidates.sortByDescending { it.second }
        return candidates.take(maxResults)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true when [typed] could plausibly have been intended as [word].
     *
     * A word is compatible when:
     *  - it starts with [typed] (exact prefix), or
     *  - its length is within 2 of [typed]'s length (allows insertions/deletions).
     *
     * Words shorter than 2 characters are excluded unless [typed] is also ≤ 1 char,
     * because single-char candidates flood the strip without adding value.
     */
    private fun isPrefixCompatible(typed: String, word: String): Boolean {
        if (typed.isEmpty()) return word.length >= 2  // next-word: exclude single chars
        if (word.length < typed.length - 2) return false
        if (word.length > typed.length + 2) return false
        // Fast-path: exact prefix
        if (word.startsWith(typed)) return true
        // Tolerate up to 1 substitution in the first character (fat-finger on first key)
        return word.length >= typed.length
    }

    /** Encodes edit-distance as a log score analogous to the spatial model. */
    private fun editDistancePenalty(typed: String, word: String): Float {
        if (word.startsWith(typed)) return 0f
        val dist = simpleEditDistance(typed, word.take(typed.length + 2))
        return -(dist.toFloat() * 4f)
    }

    /**
     * Levenshtein distance capped at 3 to keep inner-loop cost bounded.
     * Only used in the no-geometry path.
     */
    private fun simpleEditDistance(a: String, b: String): Int {
        val maxDist = 3
        if (kotlin.math.abs(a.length - b.length) > maxDist) return maxDist + 1
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
            }
        }
        return dp[a.length][b.length]
    }
}
