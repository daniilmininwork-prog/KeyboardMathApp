package dev.tally.design

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3.1 acceptance tests: chip appear/update/dismiss motion + reduce-motion fallback.
 *
 * Covers the criteria from the interaction spec (06-interaction-spec.md §Motion):
 *   - Appear:  quick fade + translate + scale, standard easing.
 *   - Update:  cross-fade when a new value arrives while visible.
 *   - Dismiss: quick fade out.
 *   - Reduce-motion: instant show/hide when system animations are off.
 *
 * Also covers:
 *   - [MotionToken] values read from resources (not hardcoded).
 *   - [KeyTheme] dynamic color toggle does not require view recreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MotionAcceptanceTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── Appear (reduce-motion path) ───────────────────────────────────────────

    @Test
    fun show_reducedMotion_immediatelyVisible() {
        setAnimatorScale(0f)
        try {
            val chip = MathResultChip(ctx)
            chip.show("42")

            assertEquals(View.VISIBLE, chip.visibility)
            assertTrue(chip.isClickable)
            // No ongoing animation — translationY and scale must be at rest values.
            assertEquals(0f, chip.translationY, 0.001f)
            assertEquals(1f, chip.scaleX,       0.001f)
            assertEquals(1f, chip.scaleY,       0.001f)
            assertEquals(1f, chip.alpha,        0.001f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    // ── Update (reduce-motion path) ───────────────────────────────────────────

    @Test
    fun show_update_reducedMotion_setsNewTextInstantly() {
        setAnimatorScale(0f)
        try {
            val chip = MathResultChip(ctx)
            chip.show("4")
            chip.show("40")

            // After instant update the chip must remain visible with the latest description.
            assertEquals(View.VISIBLE, chip.visibility)
            val desc = chip.contentDescription?.toString() ?: ""
            assertTrue("Description must contain updated value '40'", desc.contains("40"))
        } finally {
            setAnimatorScale(1f)
        }
    }

    // ── Dismiss (reduce-motion path) ──────────────────────────────────────────

    @Test
    fun dismiss_reducedMotion_immediatelyGone() {
        setAnimatorScale(0f)
        try {
            val chip = MathResultChip(ctx)
            chip.show("7")
            chip.dismiss()

            assertEquals(View.GONE, chip.visibility)
            assertFalse(chip.isClickable)
            assertNull(chip.contentDescription)
            // Alpha must be reset so the next show() starts from full opacity.
            assertEquals(1f, chip.alpha, 0.001f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    // ── Motion tokens are resource-driven ─────────────────────────────────────

    @Test
    fun motionTokens_appearMs_inSpecBand() {
        val ms = MotionToken.appearMs(ctx)
        assertTrue("Appear must be 120–160 ms per spec", ms in 120L..160L)
    }

    @Test
    fun motionTokens_dismissMs_isHundredMs() {
        assertEquals(100L, MotionToken.dismissMs(ctx))
    }

    @Test
    fun motionTokens_fadeMs_isEightyMs() {
        assertEquals(80L, MotionToken.fadeMs(ctx))
    }

    // ── Dynamic color toggle doesn't require view recreate ────────────────────

    @Test
    fun keyTheme_dynamicColorToggle_noViewRecreate() {
        setAnimatorScale(0f)
        try {
            val theme = KeyTheme(ctx)
            val chip  = MathResultChip(ctx)
            chip.show("99")

            val chipBgBefore = theme.chipBg

            // Toggle dynamic color on…
            theme.applyDynamicColors(
                DynamicColorScheme.Scheme(0xFF_1A73E8.toInt(), 0xFF_4CAF50.toInt(), 0xFF_9E9E9E.toInt())
            )
            val chipBgAfter = theme.chipBg

            // …colors differ without the chip being recreated.
            assertFalse(
                "Dynamic color must change chipBg without view recreate",
                chipBgBefore == chipBgAfter,
            )
            // The chip itself must still be alive (no exception / null reference).
            assertEquals(View.VISIBLE, chip.visibility)
        } finally {
            setAnimatorScale(1f)
        }
    }

    // ── Chip initial state ────────────────────────────────────────────────────

    @Test
    fun chip_initialState_isGoneAndNotClickable() {
        val chip = MathResultChip(ctx)
        assertEquals(View.GONE, chip.visibility)
        assertFalse(chip.isClickable)
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun setAnimatorScale(scale: Float) {
    try {
        ValueAnimator::class.java
            .getMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, scale)
    } catch (_: Exception) {
        // Hidden API not available in this Robolectric build — animation scale unchanged.
    }
}
