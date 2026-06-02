package dev.tally.keyboard.engine

/**
 * Pure geometry engine for a keyboard layout.
 *
 * Converts a data-driven [LayoutDefinition] into pixel [KeyGeometry] and provides
 * point-in-key hit-testing — both as pure functions with no Android dependencies.
 * This is the testability seam called out in the rebuild brief: all geometry and
 * hit-testing can be unit-tested without inflating any View.
 *
 * The canonical implementation is [DefaultLayoutEngine]. Callers that need to mock
 * geometry in tests can substitute their own.
 */
interface LayoutEngine {

    /**
     * Resolve [def] into pixel geometry for a viewport of [width] × [height] pixels.
     *
     * Row height is divided uniformly across all rows. Key widths within a row are
     * proportional to their [KeyDef.width] fractions scaled to [width]. If a row's
     * fractions do not sum to exactly 1.0, the residual space becomes trailing
     * padding in that row (the same rule the parser allows at parse time).
     *
     * @param def    The layout definition; must have been validated by the parser.
     * @param width  Viewport width in pixels; must be > 0.
     * @param height Viewport height in pixels; must be > 0.
     * @throws IllegalArgumentException if [width] or [height] ≤ 0, or if [def] has no rows.
     */
    fun layoutFor(def: LayoutDefinition, width: Int, height: Int): KeyGeometry

    /**
     * Returns the [KeyId] of the key whose bounding rectangle contains ([x], [y]).
     *
     * Coordinates are in the same pixel space as [geometry]. Returns null when the
     * point falls outside every key (e.g., in inter-key padding or outside the viewport).
     *
     * When a point falls exactly on the shared edge between two keys the leftmost /
     * topmost key wins (consistent with the half-open interval convention in
     * [ResolvedKey.contains]).
     *
     * @param geometry The geometry produced by [layoutFor] for the current viewport.
     * @param x        Horizontal coordinate in pixels.
     * @param y        Vertical coordinate in pixels.
     */
    fun hitTest(geometry: KeyGeometry, x: Float, y: Float): KeyId?
}

/**
 * Production implementation of [LayoutEngine].
 *
 * Uses uniform row heights and proportional key widths within each row.
 * RTL layouts (where [LayoutDefinition.direction] == [Direction.RTL]) have their
 * key order mirrored left-to-right so the logical first key appears on the right
 * of the keyboard, matching the visual expectation for right-to-left scripts.
 */
object DefaultLayoutEngine : LayoutEngine {

    override fun layoutFor(def: LayoutDefinition, width: Int, height: Int): KeyGeometry {
        require(width > 0) { "Viewport width must be > 0, got $width" }
        require(height > 0) { "Viewport height must be > 0, got $height" }
        require(def.rows.isNotEmpty()) { "LayoutDefinition '${def.id}' has no rows" }

        val rowCount  = def.rows.size
        val rowHeight = height.toFloat() / rowCount

        val resolved = mutableListOf<ResolvedKey>()
        def.rows.forEachIndexed { rowIndex, row ->
            val keys = if (def.direction == Direction.RTL) row.keys.reversed() else row.keys
            val top    = rowIndex * rowHeight
            val bottom = top + rowHeight
            var cursor = 0f
            keys.forEachIndexed { keyIndex, keyDef ->
                val keyWidth = keyDef.width * width
                val left  = cursor
                val right = cursor + keyWidth
                // For RTL layouts the iteration order is reversed but keyIndex should still
                // reflect the position within the (mirrored) rendered row, so consumers can
                // address keys by their visual position. The KeyId rowIndex is always the
                // source-definition row index so round-trips through LayoutDefinition work.
                val visualKeyIndex = if (def.direction == Direction.RTL) {
                    row.keys.size - 1 - keyIndex
                } else {
                    keyIndex
                }
                resolved += ResolvedKey(
                    id      = KeyId(rowIndex, visualKeyIndex),
                    keyDef  = keyDef,
                    left    = left,
                    top     = top,
                    right   = right,
                    bottom  = bottom,
                )
                cursor += keyWidth
            }
        }

        return KeyGeometry(
            viewportWidth  = width,
            viewportHeight = height,
            rowHeight      = rowHeight,
            keys           = resolved,
        )
    }

    override fun hitTest(geometry: KeyGeometry, x: Float, y: Float): KeyId? =
        geometry.keys.firstOrNull { it.contains(x, y) }?.id
}
