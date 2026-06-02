package dev.tally.keyboard.engine

/**
 * A single sampled point in a glide-typing trace.
 *
 * Coordinates are in the same pixel space as [KeyGeometry] — relative to the
 * keyboard's top-left corner. The [timestamp] field is in arbitrary monotonic
 * milliseconds and is used only for optional speed analysis; the decoder does not
 * require it to be wall-clock time.
 */
data class GlidePoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = 0L,
)

/**
 * The complete finger trace for a single swipe gesture.
 *
 * Accumulated in [dev.tally.keyboard.engine.PointerTracker.slidePath] and then
 * wrapped here before being handed to [GestureDecoder.decode]. An empty [points]
 * list is legal and causes [GestureDecoder.decode] to return an empty result.
 *
 * @param points  Ordered list of sampled positions, oldest first.
 */
data class GlidePath(val points: List<GlidePoint>) {
    companion object {
        val EMPTY = GlidePath(emptyList())

        /** Convenience builder from raw coordinate pairs (no timestamps). */
        fun of(vararg xy: Float): GlidePath {
            require(xy.size % 2 == 0) { "xy must be paired" }
            val pts = (xy.indices step 2).map { GlidePoint(xy[it], xy[it + 1]) }
            return GlidePath(pts)
        }
    }
}
