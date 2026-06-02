package dev.tally.design

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DynamicColorScheme] — the platform-native dynamic color groundwork.
 *
 * Verifies:
 *   - On API < 31, [DynamicColorScheme.resolve] returns null (graceful static fallback).
 *   - [DynamicColorScheme.Scheme] data class preserves seed colors faithfully.
 *   - [KeyTheme.applyDynamicColors] changes the chip and pressed-key colors.
 *   - [KeyTheme.applyDynamicColors] with null resets to static token values.
 *   - Non-override tokens (keyboardBg, keyBg, etc.) are unaffected by a dynamic scheme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // sub-31 by default — dynamic color unavailable path
class DynamicColorSchemeTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── API < 31 (no dynamic color) ───────────────────────────────────────────

    @Test
    fun resolve_belowApi31_returnsNull() {
        // Robolectric SDK is 28 in this class — dynamic color must not be available.
        val scheme = DynamicColorScheme.resolve(ctx)
        assertNull("DynamicColorScheme.resolve must return null below API 31", scheme)
    }

    // ── Scheme data class ─────────────────────────────────────────────────────

    @Test
    fun scheme_preservesSeedColors() {
        val primary   = 0xFF_1A73E8.toInt()
        val secondary = 0xFF_4CAF50.toInt()
        val neutral   = 0xFF_9E9E9E.toInt()
        val scheme    = DynamicColorScheme.Scheme(primary, secondary, neutral)

        assertEquals(primary,   scheme.primarySeed)
        assertEquals(secondary, scheme.secondarySeed)
        assertEquals(neutral,   scheme.neutralSeed)
    }

    @Test
    fun scheme_equality_reflectsDataClassSemantics() {
        val a = DynamicColorScheme.Scheme(0xFF_0000FF.toInt(), 0xFF_00FF00.toInt(), 0xFF_FF0000.toInt())
        val b = DynamicColorScheme.Scheme(0xFF_0000FF.toInt(), 0xFF_00FF00.toInt(), 0xFF_FF0000.toInt())
        val c = DynamicColorScheme.Scheme(0xFF_FF0000.toInt(), 0xFF_00FF00.toInt(), 0xFF_0000FF.toInt())

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ── KeyTheme.applyDynamicColors ───────────────────────────────────────────

    @Test
    fun applyDynamicColors_withScheme_changesChipBg() {
        val theme      = KeyTheme(ctx)
        val staticChip = theme.chipBg

        // Apply a vivid-blue primary seed.
        theme.applyDynamicColors(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_0000FF.toInt(),
            secondarySeed = 0xFF_00FF00.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        ))

        assertNotEquals("chipBg must change after applyDynamicColors", staticChip, theme.chipBg)
    }

    @Test
    fun applyDynamicColors_withScheme_changesKeyPressedBg() {
        val theme         = KeyTheme(ctx)
        val staticPressed = theme.keyPressedBg

        theme.applyDynamicColors(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_0000FF.toInt(),
            secondarySeed = 0xFF_00FF00.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        ))

        assertNotEquals("keyPressedBg must change after applyDynamicColors", staticPressed, theme.keyPressedBg)
    }

    @Test
    fun applyDynamicColors_reset_restoresStaticTokens() {
        val theme         = KeyTheme(ctx)
        val staticChip    = theme.chipBg
        val staticPressed = theme.keyPressedBg

        // Apply then reset.
        theme.applyDynamicColors(DynamicColorScheme.Scheme(0xFF_0000FF.toInt(), 0xFF_00FF00.toInt(), 0xFF_9E9E9E.toInt()))
        theme.applyDynamicColors(null)

        assertEquals("chipBg must revert to static token after null scheme", staticChip, theme.chipBg)
        assertEquals("keyPressedBg must revert to static token after null scheme", staticPressed, theme.keyPressedBg)
    }

    @Test
    fun applyDynamicColors_doesNotAffectNeutralSurfaces() {
        val theme        = KeyTheme(ctx)
        val staticKbdBg  = theme.keyboardBg
        val staticKeyBg  = theme.keyBg
        val staticKeyTxt = theme.keyText

        theme.applyDynamicColors(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_FF0000.toInt(),
            secondarySeed = 0xFF_00FF00.toInt(),
            neutralSeed   = 0xFF_0000FF.toInt(),
        ))

        assertEquals("keyboardBg must not be affected by dynamic colors", staticKbdBg, theme.keyboardBg)
        assertEquals("keyBg must not be affected by dynamic colors",      staticKeyBg, theme.keyBg)
        assertEquals("keyText must not be affected by dynamic colors",    staticKeyTxt, theme.keyText)
    }

    @Test
    fun applyDynamicColors_dynamicChipBg_hasExpectedAlpha() {
        val theme = KeyTheme(ctx)
        theme.applyDynamicColors(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_1A73E8.toInt(),
            secondarySeed = 0xFF_4CAF50.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        ))
        // The chip bg is blended at alpha=0xCC (204/255 ≈ 80%).
        val alpha = (theme.chipBg ushr 24) and 0xFF
        assertEquals("Dynamic chip bg must have alpha 0xCC", 0xCC, alpha)
    }

    @Test
    fun applyDynamicColors_dynamicKeyPressedBg_hasExpectedAlpha() {
        val theme = KeyTheme(ctx)
        theme.applyDynamicColors(DynamicColorScheme.Scheme(
            primarySeed   = 0xFF_1A73E8.toInt(),
            secondarySeed = 0xFF_4CAF50.toInt(),
            neutralSeed   = 0xFF_9E9E9E.toInt(),
        ))
        // The pressed bg is blended at alpha=0x66 (102/255 ≈ 40%).
        val alpha = (theme.keyPressedBg ushr 24) and 0xFF
        assertEquals("Dynamic key pressed bg must have alpha 0x66", 0x66, alpha)
    }
}
