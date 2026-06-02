package dev.tally.keyboard.engine

/**
 * Pure-function geometry transform for a keyboard form-factor mode.
 *
 * Given a [FormFactorMode] and the physical viewport dimensions, this object computes
 * the pixel-space rectangle that the key plane should occupy, along with whether the
 * keyboard should report bottom insets to the host (so the host can scroll content above
 * the keyboard).
 *
 * All values are dimensionless integers (pixels) so they can be tested without an Android
 * runtime. The view layer applies the returned [Result] in [onMeasure] / [onLayout] and
 * in the IME's [onComputeInsets].
 *
 * Geometry decisions per mode:
 *
 * NORMAL     — full viewport width, standard height, anchored to the bottom edge.
 * ONE_HANDED — [ONE_HANDED_WIDTH_FRACTION] of viewport width; left or right aligned;
 *              same height as NORMAL on the same screen. The narrow keyboard is easier
 *              for single-thumb reach without shrinking touch targets.
 * SPLIT      — The viewport is divided into two half-width regions. Keys are distributed
 *              across both halves; the rendering layer is responsible for the split draw.
 *              The [Result] reports the full viewport width so the view still occupies its
 *              normal footprint; [isSplit] flags the view to apply the visual split.
 * FLOATING   — [FLOATING_WIDTH_FRACTION] × [FLOATING_HEIGHT_FRACTION] of the viewport,
 *              positioned at an absolute (x, y) offset from the top-left of the window.
 *              [claimsInsets] is false: the host must not be forced to scroll away, as the
 *              keyboard floats over content rather than pushing it up.
 *
 * Height in all anchored modes is computed by [KeyboardHeightPolicy.heightPx] from the
 * current viewport metrics, so the row-height and screen-fraction cap still apply.
 */
object FormFactorTransform {

    /** Fraction of the viewport width allocated to the keyboard in ONE_HANDED mode. */
    const val ONE_HANDED_WIDTH_FRACTION = 0.55f

    /** Fraction of the viewport width allocated to the keyboard in FLOATING mode. */
    const val FLOATING_WIDTH_FRACTION = 0.72f

    /** Fraction of the keyboard height used in FLOATING mode (rows are slightly shorter). */
    const val FLOATING_HEIGHT_FRACTION = 0.85f

    /**
     * The resolved pixel dimensions and placement of the key plane for a given mode.
     *
     * @param widthPx       Width of the key plane in pixels.
     * @param heightPx      Height of the key plane in pixels.
     * @param offsetXPx     Horizontal offset from the left edge of the IME window (pixels).
     * @param offsetYPx     Vertical offset from the top of the keyboard's allocated space (pixels).
     * @param claimsInsets  Whether the keyboard should report bottom insets to the host.
     *                      False only for FLOATING mode, where the keyboard must not scroll
     *                      the host content.
     * @param isSplit       True for SPLIT mode; the view should render keys split across
     *                      two columns flanking a central gap.
     */
    data class Result(
        val widthPx: Int,
        val heightPx: Int,
        val offsetXPx: Int,
        val offsetYPx: Int,
        val claimsInsets: Boolean,
        val isSplit: Boolean,
    )

    /**
     * Computes the key-plane geometry for [mode] in a viewport of [viewportWidthPx] ×
     * [viewportHeightPx] at [density] and [rowCount] key rows.
     *
     * @param mode              The active form-factor mode.
     * @param viewportWidthPx   Available width for the IME window in pixels.
     * @param viewportHeightPx  Full screen height in pixels (used for the screen-fraction cap).
     * @param density           Display density (pixels per dp, from DisplayMetrics.density).
     * @param rowCount          Number of key rows; forwarded to [KeyboardHeightPolicy.heightPx].
     * @param oneHandedRight    When true in ONE_HANDED mode, the keyboard aligns to the right edge.
     * @param floatingOffsetXPx Horizontal pixel offset for FLOATING mode; clamped to valid range.
     * @param floatingOffsetYPx Vertical pixel offset for FLOATING mode; clamped to valid range.
     */
    fun resolve(
        mode: FormFactorMode,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Float,
        rowCount: Int,
        oneHandedRight: Boolean = false,
        floatingOffsetXPx: Int = 0,
        floatingOffsetYPx: Int = 0,
    ): Result {
        require(viewportWidthPx > 0) { "viewportWidthPx must be > 0, got $viewportWidthPx" }
        require(viewportHeightPx > 0) { "viewportHeightPx must be > 0, got $viewportHeightPx" }

        val normalHeight = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = viewportWidthPx,
            screenHeightPx = viewportHeightPx,
            density        = density,
            rowCount       = rowCount,
        )

        return when (mode) {
            FormFactorMode.NORMAL -> Result(
                widthPx      = viewportWidthPx,
                heightPx     = normalHeight,
                offsetXPx    = 0,
                offsetYPx    = 0,
                claimsInsets = true,
                isSplit      = false,
            )

            FormFactorMode.ONE_HANDED -> {
                val w = (viewportWidthPx * ONE_HANDED_WIDTH_FRACTION).toInt().coerceAtLeast(1)
                val offsetX = if (oneHandedRight) viewportWidthPx - w else 0
                Result(
                    widthPx      = w,
                    heightPx     = normalHeight,
                    offsetXPx    = offsetX,
                    offsetYPx    = 0,
                    claimsInsets = true,
                    isSplit      = false,
                )
            }

            FormFactorMode.SPLIT -> Result(
                // The whole-viewport width is reported so the view occupies its normal
                // IME footprint; the split rendering is signalled via isSplit.
                widthPx      = viewportWidthPx,
                heightPx     = normalHeight,
                offsetXPx    = 0,
                offsetYPx    = 0,
                claimsInsets = true,
                isSplit      = true,
            )

            FormFactorMode.FLOATING -> {
                val w = (viewportWidthPx * FLOATING_WIDTH_FRACTION).toInt().coerceAtLeast(1)
                val h = (normalHeight * FLOATING_HEIGHT_FRACTION).toInt().coerceAtLeast(1)

                // Clamp the user-supplied offset so the panel stays within the viewport.
                val maxX = (viewportWidthPx - w).coerceAtLeast(0)
                val maxY = (viewportHeightPx - h).coerceAtLeast(0)
                val clampedX = floatingOffsetXPx.coerceIn(0, maxX)
                val clampedY = floatingOffsetYPx.coerceIn(0, maxY)

                Result(
                    widthPx      = w,
                    heightPx     = h,
                    offsetXPx    = clampedX,
                    offsetYPx    = clampedY,
                    claimsInsets = false,
                    isSplit      = false,
                )
            }
        }
    }
}
