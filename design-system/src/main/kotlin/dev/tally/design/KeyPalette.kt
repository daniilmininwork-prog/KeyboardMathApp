package dev.tally.design

import androidx.annotation.ColorInt

/**
 * A complete, self-contained set of keyboard colour tokens.
 *
 * Where [DynamicColorScheme.Scheme] carries only a few wallpaper *seeds* that [KeyTheme] blends
 * into a couple of accent tokens, a [KeyPalette] specifies **every** token outright. Applying one
 * via [KeyTheme.applyPalette] makes the keyboard ignore the day/night resource qualifier entirely
 * and render exactly these colours — this is how the explicit Light/Dark base variants pin a
 * brightness, and how the high-contrast presets force a legible, fixed palette.
 *
 * ## Borders
 *
 * [drawBorder] / [keyBorder] let a palette request a stroked outline around every key face. Base
 * variants leave this off (flat faces, matching the stock look); high-contrast palettes turn it on
 * so each key reads as a distinct shape against the background even at extreme contrast.
 *
 * All channels are fully-opaque ARGB ints — a palette is a literal description of what is drawn,
 * with no further blending applied.
 */
data class KeyPalette(
    @ColorInt val keyboardBg: Int,
    @ColorInt val keyBg: Int,
    @ColorInt val keySpecialBg: Int,
    @ColorInt val keyPressedBg: Int,
    @ColorInt val keyText: Int,
    @ColorInt val suggestionStripBg: Int,
    @ColorInt val chipBg: Int,
    @ColorInt val chipText: Int,
    @ColorInt val keyPreviewBg: Int,
    @ColorInt val longPressPopupBg: Int,
    @ColorInt val longPressPopupSelectedBg: Int,
    /** Stroke colour for the per-key border; only consulted when [drawBorder] is true. */
    @ColorInt val keyBorder: Int,
    /** When true, [KeyTheme.drawKeyBorders] returns true and views stroke each key face. */
    val drawBorder: Boolean,
) {
    companion object {

        // ── Base variants ──────────────────────────────────────────────────────
        // LIGHT/DARK mirror the static values/ and values-night/ tokens so "force light" and
        // "force dark" look identical to the auto theme in the matching system mode. The SOLID
        // variants flatten the key faces (opaque, slightly more separated from the background)
        // for users who prefer crisp keys over the translucent stock look.

        val LIGHT = KeyPalette(
            keyboardBg               = 0xFF_D1D3D9.toInt(),
            keyBg                    = 0xFF_FFFFFF.toInt(),
            keySpecialBg             = 0xFF_ADB1B8.toInt(),
            keyPressedBg             = 0xFF_A5A7AC.toInt(),
            keyText                  = 0xFF_1C1C1E.toInt(),
            suggestionStripBg        = 0xFF_F2F2F7.toInt(),
            chipBg                   = 0xFF_FFFFFF.toInt(),
            chipText                 = 0xFF_1C1C1E.toInt(),
            keyPreviewBg             = 0xFF_FFFFFF.toInt(),
            longPressPopupBg         = 0xFF_FFFFFF.toInt(),
            longPressPopupSelectedBg = 0xFF_D1D3D9.toInt(),
            keyBorder                = 0xFF_1C1C1E.toInt(),
            drawBorder               = false,
        )

        val SOLID_LIGHT = LIGHT.copy(
            // A cooler, flatter neutral so the opaque white keys stand out crisply.
            keyboardBg               = 0xFF_C7CAD1.toInt(),
            keySpecialBg             = 0xFF_9AA0A8.toInt(),
            keyPressedBg             = 0xFF_8E939B.toInt(),
            suggestionStripBg        = 0xFF_E9EAEF.toInt(),
        )

        val DARK = KeyPalette(
            keyboardBg               = 0xFF_1C1C1E.toInt(),
            keyBg                    = 0xFF_2C2C2E.toInt(),
            keySpecialBg             = 0xFF_1C1C1E.toInt(),
            keyPressedBg             = 0xFF_636366.toInt(),
            keyText                  = 0xFF_FFFFFF.toInt(),
            suggestionStripBg        = 0xFF_1C1C1E.toInt(),
            chipBg                   = 0xFF_2C2C2E.toInt(),
            chipText                 = 0xFF_FFFFFF.toInt(),
            keyPreviewBg             = 0xFF_3A3A3C.toInt(),
            longPressPopupBg         = 0xFF_3A3A3C.toInt(),
            longPressPopupSelectedBg = 0xFF_636366.toInt(),
            keyBorder                = 0xFF_FFFFFF.toInt(),
            drawBorder               = false,
        )

        val SOLID_DARK = DARK.copy(
            // Near-black surface with opaque, lifted key faces for a flat high-separation look.
            keyboardBg               = 0xFF_000000.toInt(),
            keyBg                    = 0xFF_242426.toInt(),
            keySpecialBg             = 0xFF_111113.toInt(),
            keyPressedBg             = 0xFF_55555A.toInt(),
            suggestionStripBg        = 0xFF_000000.toInt(),
        )

        // ── High-contrast palettes ───────────────────────────────────────────────
        // Each forces a maximally-distinct text/background pair and turns borders ON so every key
        // is outlined. The pressed and special surfaces stay within the same scheme so the contrast
        // ratio never drops below the resting state.

        /** White text/keys on a pure-black background — the canonical high-contrast scheme. */
        val HC_WHITE_ON_BLACK = KeyPalette(
            keyboardBg               = 0xFF_000000.toInt(),
            keyBg                    = 0xFF_000000.toInt(),
            keySpecialBg             = 0xFF_1A1A1A.toInt(),
            keyPressedBg             = 0xFF_FFFFFF.toInt(),
            keyText                  = 0xFF_FFFFFF.toInt(),
            suggestionStripBg        = 0xFF_000000.toInt(),
            chipBg                   = 0xFF_000000.toInt(),
            chipText                 = 0xFF_FFFFFF.toInt(),
            keyPreviewBg             = 0xFF_000000.toInt(),
            longPressPopupBg         = 0xFF_000000.toInt(),
            longPressPopupSelectedBg = 0xFF_FFFFFF.toInt(),
            keyBorder                = 0xFF_FFFFFF.toInt(),
            drawBorder               = true,
        )

        /** Yellow text/borders on black — preferred by many low-vision users over white-on-black. */
        val HC_YELLOW_ON_BLACK = HC_WHITE_ON_BLACK.copy(
            keyText                  = 0xFF_FFEB3B.toInt(),
            keyBorder                = 0xFF_FFEB3B.toInt(),
            chipText                 = 0xFF_FFEB3B.toInt(),
            keyPressedBg             = 0xFF_FFEB3B.toInt(),
            longPressPopupSelectedBg = 0xFF_FFEB3B.toInt(),
        )

        /** White text/keys on a deep, saturated blue — a softer high-contrast alternative. */
        val HC_WHITE_ON_BLUE = KeyPalette(
            keyboardBg               = 0xFF_00204A.toInt(),
            keyBg                    = 0xFF_002F6C.toInt(),
            keySpecialBg             = 0xFF_001A3D.toInt(),
            keyPressedBg             = 0xFF_FFFFFF.toInt(),
            keyText                  = 0xFF_FFFFFF.toInt(),
            suggestionStripBg        = 0xFF_00204A.toInt(),
            chipBg                   = 0xFF_002F6C.toInt(),
            chipText                 = 0xFF_FFFFFF.toInt(),
            keyPreviewBg             = 0xFF_002F6C.toInt(),
            longPressPopupBg         = 0xFF_002F6C.toInt(),
            longPressPopupSelectedBg = 0xFF_FFFFFF.toInt(),
            keyBorder                = 0xFF_FFFFFF.toInt(),
            drawBorder               = true,
        )

        /** Black text/borders on white — the bordered light high-contrast variant. */
        val HC_BLACK_ON_WHITE = KeyPalette(
            keyboardBg               = 0xFF_FFFFFF.toInt(),
            keyBg                    = 0xFF_FFFFFF.toInt(),
            keySpecialBg             = 0xFF_E0E0E0.toInt(),
            keyPressedBg             = 0xFF_000000.toInt(),
            keyText                  = 0xFF_000000.toInt(),
            suggestionStripBg        = 0xFF_FFFFFF.toInt(),
            chipBg                   = 0xFF_FFFFFF.toInt(),
            chipText                 = 0xFF_000000.toInt(),
            keyPreviewBg             = 0xFF_FFFFFF.toInt(),
            longPressPopupBg         = 0xFF_FFFFFF.toInt(),
            longPressPopupSelectedBg = 0xFF_000000.toInt(),
            keyBorder                = 0xFF_000000.toInt(),
            drawBorder               = true,
        )
    }
}
