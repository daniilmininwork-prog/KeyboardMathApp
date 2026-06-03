package dev.tally.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ThemePreset].
 *
 * No Android runtime is needed — [ThemePreset] is a pure sealed class with no Android imports.
 */
class ThemePresetTest {

    // ── fromKey round-trips ───────────────────────────────────────────────────

    @Test
    fun fromKey_wallpaper_returnsWallpaperSingleton() {
        assertEquals(ThemePreset.Wallpaper, ThemePreset.fromKey("wallpaper"))
    }

    @Test
    fun fromKey_static_returnsStaticSingleton() {
        assertEquals(ThemePreset.Static, ThemePreset.fromKey("static"))
    }

    @Test
    fun fromKey_ocean_returnsOceanBuiltin() {
        assertEquals(ThemePreset.Builtin.Ocean, ThemePreset.fromKey("ocean"))
    }

    @Test
    fun fromKey_forest_returnsForestBuiltin() {
        assertEquals(ThemePreset.Builtin.Forest, ThemePreset.fromKey("forest"))
    }

    @Test
    fun fromKey_ember_returnsEmberBuiltin() {
        assertEquals(ThemePreset.Builtin.Ember, ThemePreset.fromKey("ember"))
    }

    @Test
    fun fromKey_dusk_returnsDuskBuiltin() {
        assertEquals(ThemePreset.Builtin.Dusk, ThemePreset.fromKey("dusk"))
    }

    @Test
    fun fromKey_slate_returnsSlateBuiltin() {
        assertEquals(ThemePreset.Builtin.Slate, ThemePreset.fromKey("slate"))
    }

    @Test
    fun fromKey_unknownKey_fallsBackToWallpaper() {
        // A backup-restore from a future app version may store an unknown key; fall back gracefully.
        assertEquals(ThemePreset.Wallpaper, ThemePreset.fromKey("future_preset_v99"))
    }

    // ── Key uniqueness ────────────────────────────────────────────────────────

    @Test
    fun allBuiltins_haveUniqueKeys() {
        val keys = ThemePreset.builtins().map { it.key }
        assertEquals("All builtin preset keys must be unique", keys.size, keys.distinct().size)
    }

    @Test
    fun builtins_keysDoNotCollideWithWallpaperOrStatic() {
        val reserved = setOf(ThemePreset.Wallpaper.key, ThemePreset.Static.key)
        for (builtin in ThemePreset.builtins()) {
            assertTrue("Builtin key '${builtin.key}' must not collide with reserved keys",
                builtin.key !in reserved)
        }
    }

    // ── builtins() list ───────────────────────────────────────────────────────

    @Test
    fun builtins_isNonEmpty() {
        assertTrue(ThemePreset.builtins().isNotEmpty())
    }

    @Test
    fun builtins_containsExpectedEntries() {
        val builtins = ThemePreset.builtins()
        assertTrue(ThemePreset.Builtin.Ocean  in builtins)
        assertTrue(ThemePreset.Builtin.Forest in builtins)
        assertTrue(ThemePreset.Builtin.Ember  in builtins)
        assertTrue(ThemePreset.Builtin.Dusk   in builtins)
        assertTrue(ThemePreset.Builtin.Slate  in builtins)
    }

    // ── Seed colors ───────────────────────────────────────────────────────────

    @Test
    fun builtins_allHaveNonZeroSeedColors() {
        for (builtin in ThemePreset.builtins()) {
            assertNotEquals("Builtin '${builtin.key}' seed must be non-zero", 0, builtin.seed)
        }
    }

    @Test
    fun builtins_seedColorsDiffer() {
        val seeds = ThemePreset.builtins().map { it.seed }
        assertEquals("All builtin seed colors must be distinct", seeds.size, seeds.distinct().size)
    }

    // ── Base variants (Stage 4) ───────────────────────────────────────────────

    @Test
    fun fromKey_baseVariants_roundTrip() {
        assertEquals(ThemePreset.BaseVariant.Light,      ThemePreset.fromKey("light"))
        assertEquals(ThemePreset.BaseVariant.SolidLight, ThemePreset.fromKey("solid_light"))
        assertEquals(ThemePreset.BaseVariant.Dark,       ThemePreset.fromKey("dark"))
        assertEquals(ThemePreset.BaseVariant.SolidDark,  ThemePreset.fromKey("solid_dark"))
    }

    @Test
    fun baseVariants_resolveDistinctPalettes() {
        // Each base variant must map to a different palette so the picker entries are meaningful.
        val palettes = ThemePreset.baseVariants().map { it.palette }
        assertEquals("All base-variant palettes must be distinct",
            palettes.size, palettes.distinct().size)
    }

    @Test
    fun baseVariants_lightAndDark_differOnKeyText() {
        // The whole point of forcing Light vs Dark is the key glyph contrast flips.
        assertNotEquals(
            ThemePreset.BaseVariant.Light.palette.keyText,
            ThemePreset.BaseVariant.Dark.palette.keyText,
        )
    }

    @Test
    fun baseVariants_doNotDrawBorders() {
        // Borders are a high-contrast affordance; base variants keep the flat stock look.
        for (variant in ThemePreset.baseVariants()) {
            assertEquals("Base variant '${variant.key}' must not draw borders",
                false, variant.palette.drawBorder)
        }
    }

    // ── High-contrast (Stage 4) ───────────────────────────────────────────────

    @Test
    fun fromKey_highContrast_roundTrip() {
        assertEquals(ThemePreset.HighContrast.WhiteOnBlack,  ThemePreset.fromKey("hc_white_on_black"))
        assertEquals(ThemePreset.HighContrast.YellowOnBlack, ThemePreset.fromKey("hc_yellow_on_black"))
        assertEquals(ThemePreset.HighContrast.WhiteOnBlue,   ThemePreset.fromKey("hc_white_on_blue"))
        assertEquals(ThemePreset.HighContrast.BlackOnWhite,  ThemePreset.fromKey("hc_black_on_white"))
    }

    @Test
    fun highContrast_atLeastFourPalettes() {
        assertTrue("Stage 4 requires ~4 high-contrast palettes",
            ThemePreset.highContrasts().size >= 4)
    }

    @Test
    fun highContrast_allDrawBorders() {
        // High-contrast presets MUST outline keys for legibility.
        for (hc in ThemePreset.highContrasts()) {
            assertTrue("High-contrast preset '${hc.key}' must draw borders", hc.palette.drawBorder)
        }
    }

    @Test
    fun highContrast_palettesAreDistinct() {
        val palettes = ThemePreset.highContrasts().map { it.palette }
        assertEquals("All high-contrast palettes must be distinct",
            palettes.size, palettes.distinct().size)
    }

    // ── all() / key uniqueness across the whole set ───────────────────────────

    @Test
    fun all_keysAreUniqueAcrossEveryPresetFamily() {
        val keys = ThemePreset.all().map { it.key }
        assertEquals("Every preset key across all families must be unique",
            keys.size, keys.distinct().size)
    }

    @Test
    fun all_containsWallpaperStaticBaseBuiltinAndHighContrast() {
        val all = ThemePreset.all()
        assertTrue(ThemePreset.Wallpaper in all)
        assertTrue(ThemePreset.Static in all)
        assertTrue(all.containsAll(ThemePreset.baseVariants()))
        assertTrue(all.containsAll(ThemePreset.builtins()))
        assertTrue(all.containsAll(ThemePreset.highContrasts()))
    }
}
