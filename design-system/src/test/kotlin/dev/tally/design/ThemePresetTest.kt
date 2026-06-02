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
}
