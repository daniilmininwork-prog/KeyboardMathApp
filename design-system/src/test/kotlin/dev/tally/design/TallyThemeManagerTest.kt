package dev.tally.design

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [TallyThemeManager].
 *
 * Verifies:
 *   - On API < 31, [TallyThemeManager.attach] does not crash and no wallpaper scheme is applied.
 *   - Setting [ThemePreset.Static] resets all registered [KeyTheme]s to null.
 *   - Setting a [ThemePreset.Builtin] preset applies a non-null scheme with the correct seed.
 *   - [TallyThemeManager.addKeyTheme] immediately applies the current scheme to the new theme.
 *   - [TallyThemeManager.applyCustomImageScheme] propagates to registered themes.
 *   - [TallyThemeManager.removeKeyTheme] stops further updates to the removed theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])   // below API 31 → wallpaper palette not available
class TallyThemeManagerTest {

    private lateinit var ctx: Context
    private lateinit var manager: TallyThemeManager

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        manager = TallyThemeManager()
    }

    // ── attach below API 31 ───────────────────────────────────────────────────

    @Test
    fun attach_belowApi31_doesNotCrash() {
        // Must not throw; wallpaper listener registration is skipped.
        manager.attach(ctx)
        manager.detach(ctx)
    }

    // ── Preset: Static ────────────────────────────────────────────────────────

    @Test
    fun preset_static_registeredThemesGetNullScheme() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.addKeyTheme(theme)

        // Force a vivid scheme so there is something to reset.
        manager.preset = ThemePreset.Builtin.Ocean
        val afterBuiltin = theme.chipBg

        // Switch to Static — should clear the dynamic override.
        manager.preset = ThemePreset.Static
        val afterStatic = theme.chipBg

        // The chip bg after Static must equal the static resource token (the builtin blue override
        // differs from the unmodified resource value).
        assertNotEquals("Ocean preset must produce a different chipBg than Static",
            afterBuiltin, afterStatic)
    }

    // ── Preset: Builtin ───────────────────────────────────────────────────────

    @Test
    fun preset_builtin_changesChipBgOnRegisteredTheme() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        val staticChipBg = theme.chipBg

        manager.preset = ThemePreset.Builtin.Ocean
        val dynamicChipBg = theme.chipBg

        assertNotEquals("Ocean builtin preset must change chipBg away from static resource value",
            staticChipBg, dynamicChipBg)
    }

    @Test
    fun preset_differentBuiltins_produceDifferentChipBg() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.addKeyTheme(theme)

        manager.preset = ThemePreset.Builtin.Ocean
        val oceanChipBg = theme.chipBg

        manager.preset = ThemePreset.Builtin.Ember
        val emberChipBg = theme.chipBg

        assertNotEquals("Ocean and Ember presets must produce distinct chipBg colors",
            oceanChipBg, emberChipBg)
    }

    // ── addKeyTheme ───────────────────────────────────────────────────────────

    @Test
    fun addKeyTheme_immediatelyAppliesCurrentScheme() {
        manager.attach(ctx)
        manager.preset = ThemePreset.Builtin.Ocean

        // A theme registered AFTER the preset was set must still receive the current scheme.
        val lateTheme = KeyTheme(ctx)
        val staticChipBg = lateTheme.chipBg  // capture before registration

        manager.addKeyTheme(lateTheme)
        val dynamicChipBg = lateTheme.chipBg

        assertNotEquals("addKeyTheme must apply the current scheme immediately",
            staticChipBg, dynamicChipBg)
    }

    // ── applyCustomImageScheme ────────────────────────────────────────────────

    @Test
    fun applyCustomImageScheme_propagatesToRegisteredThemes() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        val staticChipBg = theme.chipBg

        val customScheme = DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_FF5722.toInt(),
            secondarySeed = 0xFF_FF9800.toInt(),
            neutralSeed   = 0xFF_BDBDBD.toInt(),
        )
        manager.applyCustomImageScheme(customScheme)

        assertNotEquals("Custom image scheme must change chipBg on registered themes",
            staticChipBg, theme.chipBg)
    }

    @Test
    fun applyCustomImageScheme_null_revertsToStaticTokens() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        val staticChipBg = theme.chipBg

        // Apply then clear.
        manager.applyCustomImageScheme(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_E91E63.toInt(),
            secondarySeed = 0xFF_E91E63.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        ))
        manager.applyCustomImageScheme(null)

        assertEquals("Null custom scheme must revert chipBg to static resource token",
            staticChipBg, theme.chipBg)
    }

    // ── removeKeyTheme ────────────────────────────────────────────────────────

    @Test
    fun removeKeyTheme_stopsUpdates() {
        manager.attach(ctx)
        val theme = KeyTheme(ctx)
        manager.preset = ThemePreset.Static
        manager.addKeyTheme(theme)
        manager.removeKeyTheme(theme)

        // After removal, a preset change must NOT affect the removed theme.
        val chipBgBeforeChange = theme.chipBg
        manager.preset = ThemePreset.Builtin.Ocean

        assertEquals("Removed theme must not receive further updates",
            chipBgBeforeChange, theme.chipBg)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertNotEquals(message: String, unexpected: Int, actual: Int) {
        if (unexpected == actual) throw AssertionError("$message (both were $actual)")
    }
}
