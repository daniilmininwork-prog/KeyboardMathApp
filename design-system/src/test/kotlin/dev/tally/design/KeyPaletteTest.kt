package dev.tally.design

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the [KeyPalette] presets (Stage 4).
 *
 * Uses Robolectric only because the relative-luminance check below calls
 * [android.graphics.Color]; the palettes themselves are plain ARGB ints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPaletteTest {

    // ── Opacity ───────────────────────────────────────────────────────────────

    @Test
    fun allPaletteTokens_areFullyOpaque() {
        // A palette is a literal description of what is drawn — every surface must be opaque or the
        // wrong colour would show through (especially the high-contrast schemes).
        val palettes = listOf(
            KeyPalette.LIGHT, KeyPalette.SOLID_LIGHT, KeyPalette.DARK, KeyPalette.SOLID_DARK,
            KeyPalette.HC_WHITE_ON_BLACK, KeyPalette.HC_YELLOW_ON_BLACK,
            KeyPalette.HC_WHITE_ON_BLUE, KeyPalette.HC_BLACK_ON_WHITE,
        )
        for (p in palettes) {
            for (token in p.allColors()) {
                assertEquals("Palette token must be fully opaque", 0xFF, Color.alpha(token))
            }
        }
    }

    // ── Border flags ──────────────────────────────────────────────────────────

    @Test
    fun baseVariants_haveBorderOff() {
        assertEquals(false, KeyPalette.LIGHT.drawBorder)
        assertEquals(false, KeyPalette.SOLID_LIGHT.drawBorder)
        assertEquals(false, KeyPalette.DARK.drawBorder)
        assertEquals(false, KeyPalette.SOLID_DARK.drawBorder)
    }

    @Test
    fun highContrastPalettes_haveBorderOn() {
        assertTrue(KeyPalette.HC_WHITE_ON_BLACK.drawBorder)
        assertTrue(KeyPalette.HC_YELLOW_ON_BLACK.drawBorder)
        assertTrue(KeyPalette.HC_WHITE_ON_BLUE.drawBorder)
        assertTrue(KeyPalette.HC_BLACK_ON_WHITE.drawBorder)
    }

    // ── Legibility ──────────────────────────────────────────────────────────────

    @Test
    fun highContrastPalettes_haveStrongKeyTextToKeyBgContrast() {
        // The defining property of a high-contrast scheme: text vs key face exceeds the WCAG AA
        // large-text ratio (3:1) comfortably. We assert a strict 7:1 (AAA) since these palettes are
        // hand-picked maximally-distinct pairs.
        val palettes = listOf(
            KeyPalette.HC_WHITE_ON_BLACK, KeyPalette.HC_YELLOW_ON_BLACK,
            KeyPalette.HC_WHITE_ON_BLUE, KeyPalette.HC_BLACK_ON_WHITE,
        )
        for (p in palettes) {
            val ratio = contrastRatio(p.keyText, p.keyBg)
            assertTrue("High-contrast key text/face ratio must be >= 7:1 (was $ratio)", ratio >= 7.0)
        }
    }

    @Test
    fun highContrastBorder_contrastsWithKeyboardBg() {
        // The border must be visible against the keyboard background, not just the key face.
        val palettes = listOf(
            KeyPalette.HC_WHITE_ON_BLACK, KeyPalette.HC_YELLOW_ON_BLACK,
            KeyPalette.HC_WHITE_ON_BLUE, KeyPalette.HC_BLACK_ON_WHITE,
        )
        for (p in palettes) {
            val ratio = contrastRatio(p.keyBorder, p.keyboardBg)
            assertTrue("High-contrast border must contrast with the keyboard bg (was $ratio)",
                ratio >= 3.0)
        }
    }

    // ── Solid vs translucent ──────────────────────────────────────────────────

    @Test
    fun solidVariants_differFromBaseOnKeyboardBg() {
        // The "solid" twist is a flatter/cooler neutral surface, so they must not be identical.
        assertNotEquals(KeyPalette.LIGHT.keyboardBg, KeyPalette.SOLID_LIGHT.keyboardBg)
        assertNotEquals(KeyPalette.DARK.keyboardBg,  KeyPalette.SOLID_DARK.keyboardBg)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun KeyPalette.allColors(): List<Int> = listOf(
        keyboardBg, keyBg, keySpecialBg, keyPressedBg, keyText, suggestionStripBg,
        chipBg, chipText, keyPreviewBg, longPressPopupBg, longPressPopupSelectedBg, keyBorder,
    )

    // WCAG relative-luminance contrast ratio between two opaque colours.
    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel(Color.red(color))
        val g = channel(Color.green(color))
        val bl = channel(Color.blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl
    }
}
