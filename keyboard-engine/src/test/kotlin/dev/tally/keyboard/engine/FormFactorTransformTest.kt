package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FormFactorTransform] — pure-JVM, no Android runtime needed.
 *
 * Each test verifies the invariants specified in the T4.6 acceptance criteria:
 *   - NORMAL: full viewport width, standard height, claims insets, not split.
 *   - ONE_HANDED: ~55% viewport width, left or right aligned, claims insets.
 *   - SPLIT: full viewport width, standard height, claims insets, isSplit flag set.
 *   - FLOATING: reduced size, free-floating offset, does NOT claim insets.
 *   - All results have positive dimensions.
 */
class FormFactorTransformTest {

    // Typical phone portrait metrics.
    private val viewportW = 1080
    private val viewportH = 2400
    private val density   = 3.0f
    private val rows      = 4

    // ── NORMAL ───────────────────────────────────────────────────────────────

    @Test
    fun normal_widthEqualsViewport() {
        val r = resolve(FormFactorMode.NORMAL)
        assertEquals(viewportW, r.widthPx)
    }

    @Test
    fun normal_heightMatchesHeightPolicy() {
        val r = resolve(FormFactorMode.NORMAL)
        val expected = KeyboardHeightPolicy.heightPx(viewportW, viewportH, density, rows)
        assertEquals(expected, r.heightPx)
    }

    @Test
    fun normal_offsetsAreZero() {
        val r = resolve(FormFactorMode.NORMAL)
        assertEquals(0, r.offsetXPx)
        assertEquals(0, r.offsetYPx)
    }

    @Test
    fun normal_claimsInsets() {
        assertTrue(resolve(FormFactorMode.NORMAL).claimsInsets)
    }

    @Test
    fun normal_isNotSplit() {
        assertFalse(resolve(FormFactorMode.NORMAL).isSplit)
    }

    // ── ONE_HANDED ───────────────────────────────────────────────────────────

    @Test
    fun oneHanded_widthIsReducedFraction() {
        val r = resolve(FormFactorMode.ONE_HANDED)
        val expected = (viewportW * FormFactorTransform.ONE_HANDED_WIDTH_FRACTION).toInt()
        assertEquals(expected, r.widthPx)
    }

    @Test
    fun oneHanded_leftAlign_offsetXIsZero() {
        val r = resolve(FormFactorMode.ONE_HANDED, oneHandedRight = false)
        assertEquals(0, r.offsetXPx)
    }

    @Test
    fun oneHanded_rightAlign_offsetXEqualsRemainder() {
        val r = resolve(FormFactorMode.ONE_HANDED, oneHandedRight = true)
        val expectedOffset = viewportW - r.widthPx
        assertEquals(expectedOffset, r.offsetXPx)
    }

    @Test
    fun oneHanded_heightEqualsNormalHeight() {
        val normal     = resolve(FormFactorMode.NORMAL).heightPx
        val oneHanded  = resolve(FormFactorMode.ONE_HANDED).heightPx
        assertEquals(normal, oneHanded, "ONE_HANDED height must equal NORMAL height")
    }

    @Test
    fun oneHanded_claimsInsets() {
        assertTrue(resolve(FormFactorMode.ONE_HANDED).claimsInsets)
    }

    @Test
    fun oneHanded_isNotSplit() {
        assertFalse(resolve(FormFactorMode.ONE_HANDED).isSplit)
    }

    // ── SPLIT ────────────────────────────────────────────────────────────────

    @Test
    fun split_widthEqualsViewport() {
        assertEquals(viewportW, resolve(FormFactorMode.SPLIT).widthPx)
    }

    @Test
    fun split_isSplitFlagSet() {
        assertTrue(resolve(FormFactorMode.SPLIT).isSplit)
    }

    @Test
    fun split_claimsInsets() {
        assertTrue(resolve(FormFactorMode.SPLIT).claimsInsets)
    }

    @Test
    fun split_heightEqualsNormalHeight() {
        val normal = resolve(FormFactorMode.NORMAL).heightPx
        val split  = resolve(FormFactorMode.SPLIT).heightPx
        assertEquals(normal, split)
    }

    @Test
    fun split_offsetsAreZero() {
        val r = resolve(FormFactorMode.SPLIT)
        assertEquals(0, r.offsetXPx)
        assertEquals(0, r.offsetYPx)
    }

    // ── FLOATING ─────────────────────────────────────────────────────────────

    @Test
    fun floating_widthIsReducedFraction() {
        val r = resolve(FormFactorMode.FLOATING)
        val expected = (viewportW * FormFactorTransform.FLOATING_WIDTH_FRACTION).toInt()
        assertEquals(expected, r.widthPx)
    }

    @Test
    fun floating_heightIsSmallerThanNormal() {
        val normalH   = resolve(FormFactorMode.NORMAL).heightPx
        val floatingH = resolve(FormFactorMode.FLOATING).heightPx
        assertTrue(floatingH < normalH, "FLOATING height ($floatingH) must be < NORMAL ($normalH)")
    }

    @Test
    fun floating_doesNotClaimInsets() {
        assertFalse(resolve(FormFactorMode.FLOATING).claimsInsets)
    }

    @Test
    fun floating_isNotSplit() {
        assertFalse(resolve(FormFactorMode.FLOATING).isSplit)
    }

    @Test
    fun floating_offsetPassthrough() {
        val r = resolve(FormFactorMode.FLOATING, floatingOffsetX = 100, floatingOffsetY = 200)
        assertEquals(100, r.offsetXPx)
        assertEquals(200, r.offsetYPx)
    }

    @Test
    fun floating_offsetClampedAtMaxX() {
        // A very large X offset must be clamped so the panel stays in viewport.
        val r = resolve(FormFactorMode.FLOATING, floatingOffsetX = 99999)
        val maxX = (viewportW - r.widthPx).coerceAtLeast(0)
        assertEquals(maxX, r.offsetXPx, "Floating X offset must be clamped to $maxX")
    }

    @Test
    fun floating_offsetClampedAtMaxY() {
        val r = resolve(FormFactorMode.FLOATING, floatingOffsetY = 99999)
        val maxY = (viewportH - r.heightPx).coerceAtLeast(0)
        assertEquals(maxY, r.offsetYPx, "Floating Y offset must be clamped to $maxY")
    }

    @Test
    fun floating_negativeOffsetClampedToZero() {
        val r = resolve(FormFactorMode.FLOATING, floatingOffsetX = -50, floatingOffsetY = -100)
        assertEquals(0, r.offsetXPx)
        assertEquals(0, r.offsetYPx)
    }

    // ── Invariants for all modes ──────────────────────────────────────────────

    @Test
    fun allModes_dimensionsArePositive() {
        FormFactorMode.entries.forEach { mode ->
            val r = resolve(mode)
            assertTrue(r.widthPx > 0,  "$mode: widthPx must be > 0")
            assertTrue(r.heightPx > 0, "$mode: heightPx must be > 0")
        }
    }

    @Test
    fun allModes_normalAndSplitHaveHighestWidth() {
        val normalW    = resolve(FormFactorMode.NORMAL).widthPx
        val splitW     = resolve(FormFactorMode.SPLIT).widthPx
        val oneHandedW = resolve(FormFactorMode.ONE_HANDED).widthPx
        val floatingW  = resolve(FormFactorMode.FLOATING).widthPx

        assertEquals(normalW, splitW,          "NORMAL and SPLIT must share the same width")
        assertTrue(oneHandedW < normalW,       "ONE_HANDED must be narrower than NORMAL")
        assertTrue(floatingW  < normalW,       "FLOATING must be narrower than NORMAL")
    }

    @Test
    fun landscapeViewport_dimensionsStillPositive() {
        // Landscape: width > height
        FormFactorMode.entries.forEach { mode ->
            val r = FormFactorTransform.resolve(
                mode             = mode,
                viewportWidthPx  = 2400,
                viewportHeightPx = 1080,
                density          = density,
                rowCount         = rows,
            )
            assertTrue(r.widthPx  > 0, "$mode landscape: widthPx must be > 0")
            assertTrue(r.heightPx > 0, "$mode landscape: heightPx must be > 0")
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun resolve(
        mode: FormFactorMode,
        oneHandedRight:  Boolean = false,
        floatingOffsetX: Int     = 0,
        floatingOffsetY: Int     = 0,
    ) = FormFactorTransform.resolve(
        mode              = mode,
        viewportWidthPx   = viewportW,
        viewportHeightPx  = viewportH,
        density           = density,
        rowCount          = rows,
        oneHandedRight    = oneHandedRight,
        floatingOffsetXPx = floatingOffsetX,
        floatingOffsetYPx = floatingOffsetY,
    )
}
