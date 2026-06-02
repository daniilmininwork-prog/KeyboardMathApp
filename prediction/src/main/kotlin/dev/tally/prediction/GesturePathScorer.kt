package dev.tally.prediction

import dev.tally.keyboard.engine.GlidePoint
import dev.tally.keyboard.engine.KeyGeometry
import kotlin.math.sqrt

/**
 * Scores a user's swipe trace against a candidate word's ideal key-path.
 *
 * Both the raw trace and the ideal path are resampled to the same fixed point
 * count before scoring. The score is the negative mean squared distance between
 * corresponding resampled points, expressed as a log-probability analogue:
 *
 *   score = −∑ d²ᵢ / (2σ²)
 *
 * where σ is a spread parameter in the same pixel units as [KeyGeometry]. Higher
 * (less negative) is a better match.
 *
 * An early-exit heuristic prunes candidates whose first and last key centers are
 * far from the trace endpoints before the full scan. This is the standard
 * SHARK²-style optimisation and keeps the lexicon scan O(N × sampleCount) in the
 * common case.
 *
 * @param resampler  Shared [PathResampler] instance.
 * @param pathBuilder Builds ideal key-paths for candidate words.
 * @param sigma      Spread parameter in pixels; calibrated to roughly one key
 *                   width (defaults to 50 px, a reasonable mid-range keyboard).
 *                   Callers should pass [KeyGeometry.rowHeight] × 0.4f for
 *                   geometry-adaptive scaling.
 * @param endpointPruneThreshold  Maximum endpoint-to-endpoint distance (pixels)
 *                                 allowed before a candidate is skipped entirely.
 *                                 Set to [Float.MAX_VALUE] to disable pruning.
 */
internal class GesturePathScorer(
    private val resampler: PathResampler = PathResampler(),
    private val pathBuilder: IdealPathBuilder = IdealPathBuilder(),
    private val sigma: Float = 50f,
    private val endpointPruneThreshold: Float = Float.MAX_VALUE,
) {

    /**
     * Returns a finite log-score for [word] against the resampled [tracePts], or
     * [Float.NEGATIVE_INFINITY] when the candidate is pruned or cannot be scored
     * (e.g. the word has only one key on the layout).
     *
     * [avgKeyWidth] is used to scale the endpoint pruning threshold relative to
     * the current geometry if [endpointPruneThreshold] is finite.
     */
    fun score(
        word: String,
        tracePts: List<GlidePoint>,
        geometry: KeyGeometry,
        avgKeyWidth: Float,
    ): Float {
        if (tracePts.size < 2) return Float.NEGATIVE_INFINITY

        val idealRaw = pathBuilder.buildForWord(word, geometry)
        if (idealRaw.size < 2) return Float.NEGATIVE_INFINITY

        // Cheap endpoint prune: if the first or last letter's key center is too far
        // from the corresponding trace endpoint, skip the word.
        if (endpointPruneThreshold < Float.MAX_VALUE) {
            val threshold = endpointPruneThreshold * avgKeyWidth
            if (dist(tracePts.first(), idealRaw.first()) > threshold) return Float.NEGATIVE_INFINITY
            if (dist(tracePts.last(),  idealRaw.last())  > threshold) return Float.NEGATIVE_INFINITY
        }

        val idealPts = resampler.resample(idealRaw)
        val n = tracePts.size  // already resampled by the caller
        val sigma2 = sigma * sigma

        var sum = 0f
        for (i in 0 until n) {
            // Map i → j by stretching/compressing the ideal path to the same count.
            val j = (i.toFloat() / (n - 1) * (idealPts.size - 1)).toInt()
                .coerceIn(0, idealPts.size - 1)
            val dx = tracePts[i].x - idealPts[j].x
            val dy = tracePts[i].y - idealPts[j].y
            sum += (dx * dx + dy * dy)
        }
        return -(sum / (2f * sigma2 * n))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun dist(a: GlidePoint, b: GlidePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
