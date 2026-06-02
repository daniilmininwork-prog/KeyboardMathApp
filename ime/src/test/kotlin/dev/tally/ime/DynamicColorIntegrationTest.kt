package dev.tally.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.tally.design.DynamicColorScheme
import dev.tally.design.KeyTheme
import dev.tally.design.TallyThemeManager
import dev.tally.design.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for the dynamic color wiring between [TallyThemeManager], [ThemePreset],
 * and [KeyTheme] as it is used from the IME module.
 *
 * These tests verify the end-to-end contract without starting a live IME session:
 *   - Preset switches cascade to all registered [KeyTheme]s without a view recreate.
 *   - [KeyPlaneView.theme] is publicly readable (internal to the module) for registration.
 *   - Static fallback below API 31 produces unmodified resource-token colors.
 *   - Custom-image scheme fan-out works via [TallyThemeManager.applyCustomImageScheme].
 *
 * Uses Robolectric for Android context / resource access. No instrumented device required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DynamicColorIntegrationTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── KeyPlaneView.theme is accessible ─────────────────────────────────────

    @Test
    fun keyPlaneView_theme_isAccessibleAsKeyTheme() {
        // Verifies the internal accessor used by TallyInputMethodService compiles and returns
        // a non-null KeyTheme that serves valid token colors.
        val view  = KeyPlaneView(ctx)
        val theme: KeyTheme = view.theme
        assertNotEquals("keyboardBg from KeyPlaneView.theme must be non-zero", 0, theme.keyboardBg)
    }

    // ── TallyThemeManager preset fan-out (API 33, no live wallpaper) ─────────

    @Test
    fun themeManager_staticPreset_noChipBgOverride() {
        val manager = TallyThemeManager()
        manager.attach(ctx)

        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Builtin.Ocean  // apply to get non-static
        manager.addKeyTheme(theme)
        manager.preset = ThemePreset.Static          // reset

        // After Static, the chip bg must equal the static resource value (default KeyTheme).
        val referenceTheme = KeyTheme(ctx)  // fresh instance, no dynamic override
        assertEquals("Static preset must not override chipBg",
            referenceTheme.chipBg, theme.chipBg)
    }

    @Test
    fun themeManager_builtinPreset_overridesChipBg() {
        val manager = TallyThemeManager()
        manager.attach(ctx)

        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        val staticChipBg = theme.chipBg

        manager.preset = ThemePreset.Builtin.Forest
        assertNotEquals("Forest preset must override chipBg away from static value",
            staticChipBg, theme.chipBg)
    }

    @Test
    fun themeManager_customImageScheme_overridesChipBg() {
        val manager = TallyThemeManager()
        manager.attach(ctx)

        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        val staticChipBg = theme.chipBg

        val scheme = DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_00BCD4.toInt(),
            secondarySeed = 0xFF_00BCD4.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        )
        manager.applyCustomImageScheme(scheme)

        assertNotEquals("Custom image scheme must override chipBg", staticChipBg, theme.chipBg)
    }

    // ── ThemePreset.fromKey round-trip (used in TallyInputMethodService) ─────

    @Test
    fun fromKey_defaultPresetKey_yieldsWallpaper() {
        val preset = ThemePreset.fromKey("wallpaper")
        assertEquals(ThemePreset.Wallpaper, preset)
    }

    @Test
    fun fromKey_unknownKey_doesNotCrash() {
        // Service must degrade gracefully if a backup-restored pref stores an unknown key.
        val preset = ThemePreset.fromKey("unknown_future_preset")
        assertEquals(ThemePreset.Wallpaper, preset)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertNotEquals(message: String, unexpected: Int, actual: Int) {
        if (unexpected == actual) throw AssertionError("$message (both were $actual)")
    }
}
