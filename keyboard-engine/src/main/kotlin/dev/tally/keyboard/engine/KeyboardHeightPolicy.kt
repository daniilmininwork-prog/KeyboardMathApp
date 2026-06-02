package dev.tally.keyboard.engine

/**
 * Pure-function keyboard height calculator.
 *
 * Produces the pixel height for a keyboard with [rowCount] rows given the device's
 * screen dimensions. Separating this from the View lets it be unit-tested without an
 * Android runtime and makes it reusable across form-factor modes (T4.6).
 *
 * Height strategy (03 §3.2, §7.2):
 *   - Portrait: each row gets [ROW_HEIGHT_DP_PORTRAIT] dp. Row height is generous enough
 *     for comfortable touch targets on phones held one-handed.
 *   - Landscape: each row gets [ROW_HEIGHT_DP_LANDSCAPE] dp. The compacted row height
 *     keeps the keyboard from eating half the screen in horizontal orientation while still
 *     staying above the 44 dp minimum comfortable touch target recommended for keyboards.
 *   - The total must never exceed [MAX_FRACTION_OF_SCREEN] of the screen height so the
 *     keyboard cannot occlude the content field entirely on small screens.
 *
 * The caller (KeyPlaneView.onMeasure) passes raw pixel values; density conversion is done
 * here so the pure computation can be verified with exact pixel arithmetic in tests.
 */
object KeyboardHeightPolicy {

    /** Row height for portrait orientation, in dp. */
    const val ROW_HEIGHT_DP_PORTRAIT = 54f

    /** Row height for landscape orientation, in dp. */
    const val ROW_HEIGHT_DP_LANDSCAPE = 40f

    /**
     * Maximum fraction of the screen height the keyboard may occupy.
     *
     * Caps the total height on unusually tall/small screens so the keyboard never
     * covers the input field or a significant portion of the visible content.
     */
    const val MAX_FRACTION_OF_SCREEN = 0.45f

    /**
     * Returns the desired keyboard height in pixels.
     *
     * @param screenWidthPx  Current screen width in pixels (after configuration change).
     * @param screenHeightPx Current screen height in pixels.
     * @param density        Display density (pixels per dp, from DisplayMetrics.density).
     * @param rowCount       Number of key rows to accommodate (excludes the suggestion strip).
     */
    fun heightPx(
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
        rowCount: Int,
    ): Int {
        require(rowCount > 0) { "rowCount must be > 0, got $rowCount" }
        require(density > 0f) { "density must be > 0, got $density" }

        // Landscape: width > height.
        val isLandscape = screenWidthPx > screenHeightPx
        val rowHeightDp = if (isLandscape) ROW_HEIGHT_DP_LANDSCAPE else ROW_HEIGHT_DP_PORTRAIT
        val rowHeightPx = (rowHeightDp * density).toInt()

        val desired = rowHeightPx * rowCount
        val cap = (screenHeightPx * MAX_FRACTION_OF_SCREEN).toInt()
        return desired.coerceAtMost(cap).coerceAtLeast(1)
    }
}
