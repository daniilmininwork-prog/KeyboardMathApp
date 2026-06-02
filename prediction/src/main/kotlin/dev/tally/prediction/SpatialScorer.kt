package dev.tally.prediction

import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.ResolvedKey

/**
 * Scores a typed character sequence against a candidate word using a key-proximity
 * Gaussian model.
 *
 * Each typed character is treated as a noisy observation of a key center. The log
 * spatial score for a candidate word is the sum of per-position Gaussian log-likelihoods
 * evaluated at the distance between the typed key center and the candidate key center.
 *
 * When the typed input has no associated geometry (pure-string mode), the scorer falls
 * back to exact/prefix matching with a unit score.
 *
 * @param sigma  Standard deviation of the Gaussian proximity model, expressed as a
 *               fraction of the average key width.  0.35 matches the FlorisBoard
 *               statistical decoder calibration.
 */
internal class SpatialScorer(private val sigma: Float = 0.35f) {

    /**
     * Computes the log spatial-proximity score for [typed] against [candidate] given
     * [geometry].
     *
     * Both [typed] and [candidate] are lower-case. Returns [Float.NEGATIVE_INFINITY]
     * when the strings have incompatible lengths for spatial matching (> 2 length diff),
     * or a finite log-probability otherwise.
     *
     * The result is not normalised — callers combine it with the LM log-probability.
     */
    fun logScore(typed: String, candidate: String, geometry: KeyGeometry): Float {
        if (candidate.isEmpty()) return Float.NEGATIVE_INFINITY
        // Allow up to ±2 length difference (insertion / deletion errors).
        if (kotlin.math.abs(typed.length - candidate.length) > 2) {
            return Float.NEGATIVE_INFINITY
        }

        val avgKeyWidth = averageKeyWidth(geometry)
        val sigma2 = (sigma * avgKeyWidth) * (sigma * avgKeyWidth)

        var logSum = 0f
        val minLen = minOf(typed.length, candidate.length)

        for (i in 0 until minLen) {
            val typedKey    = keyForChar(typed[i], geometry)
            val candidateKey = keyForChar(candidate[i], geometry)

            logSum += if (typedKey != null && candidateKey != null) {
                val dx = typedKey.centerX - candidateKey.centerX
                val dy = typedKey.centerY - candidateKey.centerY
                val dist2 = dx * dx + dy * dy
                // Gaussian log-likelihood (up to a constant log(1/(2πσ²)))
                -dist2 / (2f * sigma2)
            } else {
                // No geometry: exact match scores 0; mismatch scores a small penalty.
                if (typed[i].lowercaseChar() == candidate[i].lowercaseChar()) 0f else -4f
            }
        }

        // Length-mismatch penalty: each unmatched character costs 4 nats.
        val lengthDiff = kotlin.math.abs(typed.length - candidate.length)
        logSum -= (lengthDiff * 4f)

        return logSum
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun keyForChar(ch: Char, geometry: KeyGeometry): ResolvedKey? {
        val lower = ch.lowercaseChar()
        return geometry.keys.firstOrNull { key ->
            key.keyDef.label.firstOrNull()?.lowercaseChar() == lower
        }
    }

    private fun averageKeyWidth(geometry: KeyGeometry): Float {
        if (geometry.keys.isEmpty()) return 1f
        return geometry.keys.map { it.width }.average().toFloat()
    }
}
