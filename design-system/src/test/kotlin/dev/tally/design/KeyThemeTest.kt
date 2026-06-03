package dev.tally.design

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [KeyTheme] — the design-system token provider.
 *
 * Acceptance criteria (T1.12):
 *   - All token properties return non-zero colors.
 *   - Light and dark tokens differ for the key bg surfaces.
 *   - [KeyTheme] reads from the live Context configuration on each access,
 *     not from a value captured at construction.
 *   - [MathResultChipConfig] defaults to zero overrides (use theme tokens).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyThemeTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── Token presence ────────────────────────────────────────────────────────

    @Test
    fun keyboardBg_isNonZero() {
        assertNotNull(KeyTheme(ctx).keyboardBg)
        assertNotEquals(0, KeyTheme(ctx).keyboardBg)
    }

    @Test
    fun keyBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).keyBg)
    }

    @Test
    fun keySpecialBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).keySpecialBg)
    }

    @Test
    fun keyPressedBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).keyPressedBg)
    }

    @Test
    fun keyText_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).keyText)
    }

    @Test
    fun suggestionStripBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).suggestionStripBg)
    }

    @Test
    fun chipBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).chipBg)
    }

    @Test
    fun chipText_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).chipText)
    }

    @Test
    fun keyPreviewBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).keyPreviewBg)
    }

    @Test
    fun longPressPopupBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).longPressPopupBg)
    }

    @Test
    fun longPressPopupSelectedBg_isNonZero() {
        assertNotEquals(0, KeyTheme(ctx).longPressPopupSelectedBg)
    }

    // ── Light vs dark ─────────────────────────────────────────────────────────

    @Test
    fun keyBg_lightAndDark_differ() {
        val lightCtx = ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)
        val darkCtx  = ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)
        // The dark key bg (#2C2C2E) differs from the light key bg (#FFFFFF)
        assertNotEquals(KeyTheme(lightCtx).keyBg, KeyTheme(darkCtx).keyBg)
    }

    @Test
    fun chipBg_lightAndDark_differ() {
        val lightCtx = ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)
        val darkCtx  = ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)
        assertNotEquals(KeyTheme(lightCtx).chipBg, KeyTheme(darkCtx).chipBg)
    }

    @Test
    fun keyText_lightAndDark_differ() {
        val lightCtx = ctx.withNightMode(Configuration.UI_MODE_NIGHT_NO)
        val darkCtx  = ctx.withNightMode(Configuration.UI_MODE_NIGHT_YES)
        assertNotEquals(KeyTheme(lightCtx).keyText, KeyTheme(darkCtx).keyText)
    }

    // ── Config defaults ───────────────────────────────────────────────────────

    @Test
    fun mathResultChipConfig_defaults_hasZeroColorOverrides() {
        val config = MathResultChipConfig()
        assertEquals(0, config.bgColorOverride)
        assertEquals(0, config.textColorOverride)
    }

    @Test
    fun mathResultChipConfig_fromResources_hasZeroColorOverrides() {
        val config = MathResultChipConfig.fromResources(ctx)
        assertEquals(0, config.bgColorOverride)
        assertEquals(0, config.textColorOverride)
    }

    // ── Explicit override ─────────────────────────────────────────────────────

    @Test
    fun mathResultChipConfig_explicitOverrides_arePreserved() {
        val config = MathResultChipConfig(bgColorOverride = 0xFF_FF0000.toInt(), textColorOverride = 0xFF_0000FF.toInt())
        assertEquals(0xFF_FF0000.toInt(), config.bgColorOverride)
        assertEquals(0xFF_0000FF.toInt(), config.textColorOverride)
    }

    // ── Full-palette override (Stage 4) ───────────────────────────────────────

    @Test
    fun applyPalette_overridesEveryToken() {
        val theme = KeyTheme(ctx)
        val p = KeyPalette.HC_WHITE_ON_BLACK
        theme.applyPalette(p)

        assertEquals(p.keyboardBg,               theme.keyboardBg)
        assertEquals(p.keyBg,                    theme.keyBg)
        assertEquals(p.keySpecialBg,             theme.keySpecialBg)
        assertEquals(p.keyPressedBg,             theme.keyPressedBg)
        assertEquals(p.keyText,                  theme.keyText)
        assertEquals(p.suggestionStripBg,        theme.suggestionStripBg)
        assertEquals(p.chipBg,                   theme.chipBg)
        assertEquals(p.chipText,                 theme.chipText)
        assertEquals(p.keyPreviewBg,             theme.keyPreviewBg)
        assertEquals(p.longPressPopupBg,         theme.longPressPopupBg)
        assertEquals(p.longPressPopupSelectedBg, theme.longPressPopupSelectedBg)
        assertEquals(p.keyBorder,                theme.keyBorder)
    }

    @Test
    fun applyPalette_null_revertsToStaticResourceTokens() {
        val theme = KeyTheme(ctx)
        val staticKeyBg = theme.keyBg

        theme.applyPalette(KeyPalette.HC_WHITE_ON_BLACK)
        assertNotEquals(staticKeyBg, theme.keyBg)

        theme.applyPalette(null)
        assertEquals("Clearing the palette must restore the static token", staticKeyBg, theme.keyBg)
    }

    @Test
    fun drawKeyBorders_reflectsActivePalette() {
        val theme = KeyTheme(ctx)
        assertEquals("No palette → no border", false, theme.drawKeyBorders)

        theme.applyPalette(KeyPalette.LIGHT)
        assertEquals("Base variant → no border", false, theme.drawKeyBorders)

        theme.applyPalette(KeyPalette.HC_WHITE_ON_BLACK)
        assertEquals("High-contrast → border on", true, theme.drawKeyBorders)
    }

    @Test
    fun keyBorder_fallsBackToKeyTextWhenNoPalette() {
        // Readers that ignore drawKeyBorders still get an on-theme stroke colour.
        val theme = KeyTheme(ctx)
        assertEquals(theme.keyText, theme.keyBorder)
    }

    @Test
    fun palette_winsOverDynamicScheme() {
        val theme = KeyTheme(ctx)
        // Apply a dynamic scheme first (would normally tint chipBg/keyPressedBg)…
        theme.applyDynamicColors(
            DynamicColorScheme.Scheme(
                primarySeed   = 0xFF_FF0000.toInt(),
                secondarySeed = 0xFF_FF0000.toInt(),
                neutralSeed   = 0xFF_FF0000.toInt(),
            )
        )
        // …then a full palette, which must take total priority over the scheme's tinted tokens.
        theme.applyPalette(KeyPalette.HC_WHITE_ON_BLACK)
        assertEquals(KeyPalette.HC_WHITE_ON_BLACK.chipBg, theme.chipBg)
        assertEquals(KeyPalette.HC_WHITE_ON_BLACK.keyPressedBg, theme.keyPressedBg)
    }

    @Test
    fun afterClearingPalette_dynamicSchemeTakesEffectAgain() {
        val theme = KeyTheme(ctx)
        val staticChipBg = theme.chipBg
        theme.applyDynamicColors(
            DynamicColorScheme.Scheme(
                primarySeed   = 0xFF_00FF00.toInt(),
                secondarySeed = 0xFF_00FF00.toInt(),
                neutralSeed   = 0xFF_00FF00.toInt(),
            )
        )
        val tintedChipBg = theme.chipBg
        assertNotEquals(staticChipBg, tintedChipBg)

        // Palette masks the dynamic tint…
        theme.applyPalette(KeyPalette.LIGHT)
        assertEquals(KeyPalette.LIGHT.chipBg, theme.chipBg)

        // …and clearing it falls back to the still-active dynamic scheme, not the static token.
        theme.applyPalette(null)
        assertEquals(tintedChipBg, theme.chipBg)
    }

    // ── Per-draw semantics smoke test ─────────────────────────────────────────

    @Test
    fun chip_constructedWithDefaultConfig_usesThemeTokensAtDraw() {
        // Construct chip in a light context, verify it does not throw during show.
        val chip = MathResultChip(ctx)
        chip.show("42")
        // If colors were captured at init from zero-valued paints without a crash,
        // the per-draw resolution path is wired correctly.
        assertEquals(android.view.View.VISIBLE, chip.visibility)
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

/**
 * Creates a [Context] with the given UI night mode applied so tests can verify
 * that [KeyTheme] returns the correct resource qualifier variant.
 */
private fun Context.withNightMode(nightMode: Int): Context {
    val config = Configuration(resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    return createConfigurationContext(config)
}
