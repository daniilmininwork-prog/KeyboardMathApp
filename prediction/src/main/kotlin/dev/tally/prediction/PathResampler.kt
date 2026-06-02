package dev.tally.prediction

import dev.tally.keyboard.engine.GlidePoint
import kotlin.math.sqrt

/**
 * Resamples a raw finger trace to a fixed number of evenly-spaced points.
 *
 * Uniform spatial resampling is the standard first step in statistical gesture
 * classifiers (FlorisBoard, SHARK²): it decouples the word score from drawing
 * speed and removes the dependency on how densely the input system reports touch
 * events.
 *
 * The resampled path preserves the start and end points exactly; intermediate
 * points are linearly interpolated along the cumulative arc length.
 *
 * @param targetCount  Number of points in the resampled output. Must be ≥ 2.
 *                     The FlorisBoard decoder uses 32; 16 is acceptable for a
 *                     lower-power scoring pass.
 */
internal class PathResampler(private val targetCount: Int = 32) {

    init {
        require(targetCount >= 2) { "targetCount must be ≥ 2" }
    }

    /**
     * Returns a list of exactly [targetCount] evenly-spaced points along the
     * arc defined by [points].
     *
     * Returns an empty list if [points] has fewer than 2 elements (no arc to
     * resample). Returns [points] as-is (converted to a new list) when
     * [points].size == 1.
     */
    fun resample(points: List<GlidePoint>): List<GlidePoint> {
        if (points.size < 2) return points.toList()

        // Compute cumulative arc-length at each original sample.
        val arcLen = FloatArray(points.size)
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            arcLen[i] = arcLen[i - 1] + sqrt(dx * dx + dy * dy)
        }

        val totalLen = arcLen.last()
        if (totalLen == 0f) {
            // All points are co-located; return the single location repeated.
            return List(targetCount) { points.first() }
        }

        val result = ArrayList<GlidePoint>(targetCount)
        result.add(points.first())

        var srcIdx = 0
        for (k in 1 until targetCount - 1) {
            val targetArc = totalLen * k.toFloat() / (targetCount - 1).toFloat()
            // Advance srcIdx until arcLen[srcIdx+1] >= targetArc.
            while (srcIdx + 1 < points.size - 1 && arcLen[srcIdx + 1] < targetArc) {
                srcIdx++
            }
            val t = if (arcLen[srcIdx + 1] - arcLen[srcIdx] < 1e-6f) {
                0f
            } else {
                (targetArc - arcLen[srcIdx]) / (arcLen[srcIdx + 1] - arcLen[srcIdx])
            }
            val p0 = points[srcIdx]
            val p1 = points[srcIdx + 1]
            result.add(GlidePoint(
                x = p0.x + t * (p1.x - p0.x),
                y = p0.y + t * (p1.y - p0.y),
            ))
        }

        result.add(points.last())
        return result
    }
}
