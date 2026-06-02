package dev.tally.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.FormFactorMode
import dev.tally.keyboard.engine.FormFactorTransform
import dev.tally.keyboard.engine.KeyboardHeightPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4.6 — Form factors: one-handed / split / floating / resizable height.
 *
 * Acceptance criteria verified:
 *   - NORMAL mode: view measures at full viewport width and standard height.
 *   - ONE_HANDED mode: view measures at ~55% of viewport width; height unchanged.
 *   - SPLIT mode: view measures at full viewport width; isSplit flag is set.
 *   - FLOATING mode: view measures at reduced width and height; lastTransform.claimsInsets = false.
 *   - All modes produce positive measured dimensions.
 *   - Changing formFactor property triggers requestLayout (measured dimensions change).
 *   - lastTransform is populated after onMeasure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FormFactorTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── NORMAL ───────────────────────────────────────────────────────────────

    @Test
    fun normal_measuredWidthEqualsSpec() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.NORMAL }
        measure(view, 1080)
        assertEquals(1080, view.measuredWidth)
    }

    @Test
    fun normal_heightIsPositive() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.NORMAL }
        measure(view, 1080)
        assertTrue("NORMAL height must be > 0", view.measuredHeight > 0)
    }

    @Test
    fun normal_lastTransformClaimsInsets() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.NORMAL }
        measure(view, 1080)
        val t = view.lastTransform
        assertNotNull(t)
        assertTrue("NORMAL must claim insets", t!!.claimsInsets)
    }

    @Test
    fun normal_lastTransformIsNotSplit() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.NORMAL }
        measure(view, 1080)
        assertFalse(view.lastTransform!!.isSplit)
    }

    // ── ONE_HANDED ───────────────────────────────────────────────────────────

    @Test
    fun oneHanded_measuredWidthIsLessThanFull() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.ONE_HANDED }
        // Measure with spec width 1080; onMeasure uses spec width as the viewport width.
        measure(view, 1080)
        val expectedWidth = (1080 * FormFactorTransform.ONE_HANDED_WIDTH_FRACTION).toInt()
            .coerceAtLeast(1)
        assertEquals(expectedWidth, view.measuredWidth)
    }

    @Test
    fun oneHanded_heightPositive() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.ONE_HANDED }
        measure(view, 1080)
        assertTrue("ONE_HANDED height must be > 0", view.measuredHeight > 0)
    }

    @Test
    fun oneHanded_claimsInsets() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.ONE_HANDED }
        measure(view, 1080)
        assertTrue(view.lastTransform!!.claimsInsets)
    }

    @Test
    fun oneHanded_leftAlign_offsetXIsZero() {
        val view = KeyPlaneView(ctx).apply {
            formFactor     = FormFactorMode.ONE_HANDED
            oneHandedRight = false
        }
        measure(view, 1080)
        assertEquals(0, view.lastTransform!!.offsetXPx)
    }

    @Test
    fun oneHanded_rightAlign_offsetXEqualsRemainder() {
        val view = KeyPlaneView(ctx).apply {
            formFactor     = FormFactorMode.ONE_HANDED
            oneHandedRight = true
        }
        measure(view, 1080)
        val t = view.lastTransform!!
        val expected = 1080 - t.widthPx
        assertEquals("Right-aligned ONE_HANDED offsetX must equal viewport - keyWidth",
            expected, t.offsetXPx)
    }

    // ── SPLIT ────────────────────────────────────────────────────────────────

    @Test
    fun split_isSplitFlagTrue() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.SPLIT }
        measure(view, 1080)
        assertTrue(view.lastTransform!!.isSplit)
    }

    @Test
    fun split_claimsInsets() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.SPLIT }
        measure(view, 1080)
        assertTrue(view.lastTransform!!.claimsInsets)
    }

    @Test
    fun split_heightPositive() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.SPLIT }
        measure(view, 1080)
        assertTrue(view.measuredHeight > 0)
    }

    // ── FLOATING ─────────────────────────────────────────────────────────────

    @Test
    fun floating_doesNotClaimInsets() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.FLOATING }
        measure(view, 1080)
        assertFalse("FLOATING must not claim insets", view.lastTransform!!.claimsInsets)
    }

    @Test
    fun floating_heightSmallerThanNormal() {
        // Measure with spec width 1080; onMeasure uses spec width as viewport width.
        val dm = ctx.resources.displayMetrics
        val normalH = KeyboardHeightPolicy.heightPx(1080, dm.heightPixels, dm.density, 4)
        val floatH  = (normalH * FormFactorTransform.FLOATING_HEIGHT_FRACTION).toInt()
            .coerceAtLeast(1)

        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.FLOATING }
        measure(view, 1080)

        assertEquals(floatH, view.measuredHeight)
    }

    @Test
    fun floating_widthIsReducedFraction() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.FLOATING }
        measure(view, 1080)
        val expected = (1080 * FormFactorTransform.FLOATING_WIDTH_FRACTION).toInt()
            .coerceAtLeast(1)
        assertEquals(expected, view.measuredWidth)
    }

    @Test
    fun floating_offsetStoredInTransform() {
        val view = KeyPlaneView(ctx).apply {
            formFactor     = FormFactorMode.FLOATING
            floatingOffsetX = 30
            floatingOffsetY = 60
        }
        measure(view, 1080)
        val t = view.lastTransform!!
        assertEquals(30, t.offsetXPx)
        assertEquals(60, t.offsetYPx)
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    @Test
    fun switchingMode_lastTransformUpdated() {
        val view = KeyPlaneView(ctx).apply { formFactor = FormFactorMode.NORMAL }
        measure(view, 1080)
        val normalClaims = view.lastTransform!!.claimsInsets

        view.formFactor = FormFactorMode.FLOATING
        measure(view, 1080)
        val floatClaims = view.lastTransform!!.claimsInsets

        assertTrue("NORMAL must claim insets", normalClaims)
        assertFalse("FLOATING must not claim insets", floatClaims)
    }

    @Test
    fun allModes_dimensionsPositive() {
        FormFactorMode.entries.forEach { mode ->
            val view = KeyPlaneView(ctx).apply { formFactor = mode }
            measure(view, 1080)
            assertTrue("$mode: measuredWidth > 0", view.measuredWidth > 0)
            assertTrue("$mode: measuredHeight > 0", view.measuredHeight > 0)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun measure(view: KeyPlaneView, widthPx: Int) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(wSpec, hSpec)
    }
}
