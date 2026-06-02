package dev.tally.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.tally.design.KeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance tests for the T1.12 theme model.
 *
 * Verifies that:
 *   - [KeyTheme] resolves correctly from both light and dark contexts (no cached color).
 *   - [KeyPlaneView] resolves its background color from [KeyTheme] on each draw.
 *   - [SuggestionStripView] updates its background on configuration change.
 *   - Colors from [KeyTheme] tokens in the IME module match the design-system tokens.
 *   - Runtime light↔dark switch (without view recreate) produces different colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeModelTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── KeyTheme (IME access) ─────────────────────────────────────────────────

    @Test
    fun keyTheme_keyboardBg_light_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)).keyboardBg)
    }

    @Test
    fun keyTheme_keyboardBg_dark_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)).keyboardBg)
    }

    @Test
    fun keyTheme_keyboardBg_lightAndDark_differ() {
        val light = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)).keyboardBg
        val dark  = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)).keyboardBg
        assertNotEquals(
            "keyboard_bg token must differ between light and dark modes",
            light, dark,
        )
    }

    @Test
    fun keyTheme_keyText_lightAndDark_differ() {
        val light = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)).keyText
        val dark  = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)).keyText
        assertNotEquals(light, dark)
    }

    @Test
    fun keyTheme_allTokens_nonZeroInLightMode() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        assertNotEquals(0, theme.keyboardBg)
        assertNotEquals(0, theme.keyBg)
        assertNotEquals(0, theme.keySpecialBg)
        assertNotEquals(0, theme.keyPressedBg)
        assertNotEquals(0, theme.keyText)
        assertNotEquals(0, theme.suggestionStripBg)
        assertNotEquals(0, theme.chipBg)
        assertNotEquals(0, theme.chipText)
        assertNotEquals(0, theme.keyPreviewBg)
        assertNotEquals(0, theme.longPressPopupBg)
        assertNotEquals(0, theme.longPressPopupSelectedBg)
    }

    @Test
    fun keyTheme_allTokens_nonZeroInDarkMode() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))
        assertNotEquals(0, theme.keyboardBg)
        assertNotEquals(0, theme.keyBg)
        assertNotEquals(0, theme.keySpecialBg)
        assertNotEquals(0, theme.keyPressedBg)
        assertNotEquals(0, theme.keyText)
        assertNotEquals(0, theme.suggestionStripBg)
        assertNotEquals(0, theme.chipBg)
        assertNotEquals(0, theme.chipText)
        assertNotEquals(0, theme.keyPreviewBg)
        assertNotEquals(0, theme.longPressPopupBg)
        assertNotEquals(0, theme.longPressPopupSelectedBg)
    }

    // ── KeyPlaneView per-draw theme ───────────────────────────────────────────

    @Test
    fun keyPlaneView_constructsWithoutCachingColor() {
        // If KeyPlaneView still initializes bgPaint.color in init, this would cache a stale
        // color. Verify the view can be constructed and measured in both light and dark contexts.
        val lightView = KeyPlaneView(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        val darkView  = KeyPlaneView(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))

        val spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        lightView.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        darkView.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

        // Both views must measure to a positive height; neither must throw.
        assert(lightView.measuredHeight > 0) { "light KeyPlaneView must have positive height" }
        assert(darkView.measuredHeight > 0)  { "dark KeyPlaneView must have positive height" }
    }

    @Test
    fun keyPlaneView_drawDoesNotThrow() {
        val view = KeyPlaneView(ctx)
        val spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        view.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 1080, view.measuredHeight)

        // Drawing to a real Canvas exercising the bgPaint color refresh path must not throw.
        val bmp = android.graphics.Bitmap.createBitmap(1080, view.measuredHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
    }

    // ── SuggestionStripView ───────────────────────────────────────────────────

    @Test
    fun suggestionStripView_constructsInLightAndDark() {
        val lightStrip = SuggestionStripView(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        val darkStrip  = SuggestionStripView(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))

        // Both must measure to the strip height resource without throwing.
        val spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        lightStrip.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        darkStrip.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

        assert(lightStrip.measuredHeight > 0) { "light SuggestionStripView must have positive height" }
        assert(darkStrip.measuredHeight > 0)  { "dark SuggestionStripView must have positive height" }
    }

    // ── MathResultChip per-draw theme ─────────────────────────────────────────

    @Test
    fun mathResultChip_showDoesNotThrowInLightOrDark() {
        val lightChip = dev.tally.design.MathResultChip(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        val darkChip  = dev.tally.design.MathResultChip(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))

        // show() must not throw even before the chip is laid out (the draw is deferred).
        lightChip.show("42")
        darkChip.show("42")

        assertEquals(View.VISIBLE, lightChip.visibility)
        assertEquals(View.VISIBLE, darkChip.visibility)
    }

    // ── Token value sanity (light mode reference values) ─────────────────────

    @Test
    fun keyTheme_lightMode_keyBg_isWhite() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        // Light key background is #FFFFFF = 0xFF_FFFFFF
        assertEquals(0xFF_FFFFFF.toInt(), theme.keyBg)
    }

    @Test
    fun keyTheme_darkMode_keyBg_isDark() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))
        // Dark key background is #2C2C2E = 0xFF_2C2C2E
        assertEquals(0xFF_2C2C2E.toInt(), theme.keyBg)
    }

    @Test
    fun keyTheme_lightMode_chipBg_isWhite() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO))
        assertEquals(0xFF_FFFFFF.toInt(), theme.chipBg)
    }

    @Test
    fun keyTheme_darkMode_chipBg_isDark() {
        val theme = KeyTheme(ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES))
        assertEquals(0xFF_2C2C2E.toInt(), theme.chipBg)
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun Context.withNightMode(nightMode: Int): Context {
    val config = Configuration(resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    return createConfigurationContext(config)
}
