package dev.tally.design

import androidx.annotation.ColorInt

/**
 * The set of keyboard theme options exposed to the user.
 *
 * A preset resolves to ONE of three things when applied by [TallyThemeManager]:
 *
 *  1. A wallpaper-derived [DynamicColorScheme.Scheme] ([Wallpaper]).
 *  2. A fixed-seed [DynamicColorScheme.Scheme] ([Builtin]) that tints the chip/pressed accent.
 *  3. A full [KeyPalette] override ([BaseVariant], [HighContrast]) that replaces *every* token
 *     so the keyboard ignores the day/night resource qualifier and (for high-contrast) draws
 *     clear key borders for legibility.
 *
 * The IME reads the active preset from [TallyThemeManager] which fans the resolved result out to
 * every registered [KeyTheme].
 *
 * ## Adding presets
 *
 * Extend [Builtin] (seed only) or [HighContrast] (full palette) with a new data object. The IME
 * and settings screen pick the entry up automatically through [ThemePreset.all].
 *
 * ## Serialization
 *
 * [key] is the stable string stored in SharedPreferences. Never change an existing key — doing so
 * silently resets every user's saved choice to [Wallpaper] via [fromKey].
 */
sealed class ThemePreset(val key: String) {

    /**
     * Derive the tonal palette from the current system wallpaper (Android 12+).
     * On older versions the keyboard falls back to static resource tokens.
     */
    data object Wallpaper : ThemePreset("wallpaper")

    /**
     * Ignore the wallpaper and use the static design-system tokens unchanged.
     * The tokens still follow the system day/night qualifier — this is the "auto light/dark"
     * option and the safe default on API < 31.
     */
    data object Static : ThemePreset("static")

    /**
     * A base variant that pins the keyboard to a specific [KeyPalette] regardless of the system
     * day/night setting.
     *
     * Unlike [Static] (which follows the OS night-mode qualifier) these presets let the user force
     * a light or dark keyboard while keeping the system in the opposite mode — a common Samsung /
     * Gboard option. "Solid" variants use opaque, flat key faces with a slightly higher-contrast
     * neutral surface for users who dislike the translucent/blended look.
     *
     * @param key     stable SharedPreferences key.
     * @param palette the full token set applied to every [KeyTheme].
     */
    sealed class BaseVariant(key: String, val palette: KeyPalette) : ThemePreset(key) {

        data object Light      : BaseVariant("light",       KeyPalette.LIGHT)
        data object SolidLight : BaseVariant("solid_light", KeyPalette.SOLID_LIGHT)
        data object Dark       : BaseVariant("dark",        KeyPalette.DARK)
        data object SolidDark  : BaseVariant("solid_dark",  KeyPalette.SOLID_DARK)

        companion object {
            /** All base variants in display order. */
            fun all(): List<BaseVariant> = listOf(Light, SolidLight, Dark, SolidDark)
        }
    }

    /**
     * A built-in accent preset with a fixed primary seed color.
     *
     * The seed drives [KeyTheme.applyDynamicColors] in exactly the same way as a wallpaper-derived
     * scheme, so the blend/alpha logic is identical — only the hue origin differs. The neutral
     * surfaces stay on the static day/night tokens.
     *
     * @param key  stable SharedPreferences key; must be unique across all [Builtin] instances.
     * @param seed ARGB primary seed color. Alpha channel is ignored during blending.
     */
    sealed class Builtin(key: String, @ColorInt val seed: Int) : ThemePreset(key) {

        data object Ocean  : Builtin("ocean",  0xFF_1565C0.toInt())
        data object Forest : Builtin("forest", 0xFF_2E7D32.toInt())
        data object Ember  : Builtin("ember",  0xFF_BF360C.toInt())
        data object Dusk   : Builtin("dusk",   0xFF_6A1B9A.toInt())
        data object Slate  : Builtin("slate",  0xFF_37474F.toInt())

        /** All built-in presets in display order. */
        companion object {
            fun all(): List<Builtin> = listOf(Ocean, Forest, Ember, Dusk, Slate)
        }
    }

    /**
     * A high-contrast palette intended for low-vision accessibility.
     *
     * High-contrast presets ALWAYS draw a clear key border (the [KeyPalette.keyBorder] token, with
     * [KeyPalette.drawBorder] = true) so each key face is legible against the keyboard background
     * even at extreme contrast ratios, and they replace every colour token — overriding any active
     * dynamic-color/wallpaper or base-variant theme while selected. The override is total: selecting
     * a high-contrast preset supersedes whatever else was active.
     *
     * @param key     stable SharedPreferences key.
     * @param palette the full high-contrast token set (always border-on).
     */
    sealed class HighContrast(key: String, val palette: KeyPalette) : ThemePreset(key) {

        data object WhiteOnBlack  : HighContrast("hc_white_on_black",  KeyPalette.HC_WHITE_ON_BLACK)
        data object YellowOnBlack : HighContrast("hc_yellow_on_black", KeyPalette.HC_YELLOW_ON_BLACK)
        data object WhiteOnBlue   : HighContrast("hc_white_on_blue",   KeyPalette.HC_WHITE_ON_BLUE)
        data object BlackOnWhite  : HighContrast("hc_black_on_white",  KeyPalette.HC_BLACK_ON_WHITE)

        companion object {
            /** All high-contrast presets in display order. */
            fun all(): List<HighContrast> = listOf(WhiteOnBlack, YellowOnBlack, WhiteOnBlue, BlackOnWhite)
        }
    }

    companion object {

        /** Every [Builtin] accent preset in display order; excludes [Wallpaper]/[Static]. */
        fun builtins(): List<Builtin> = Builtin.all()

        /** Every [BaseVariant] in display order. */
        fun baseVariants(): List<BaseVariant> = BaseVariant.all()

        /** Every [HighContrast] preset in display order. */
        fun highContrasts(): List<HighContrast> = HighContrast.all()

        /**
         * Every selectable preset in display order: [Wallpaper], [Static], the base variants, the
         * accent builtins, then the high-contrast palettes. Used to build the settings picker so a
         * newly-added preset shows up without editing the screen.
         */
        fun all(): List<ThemePreset> =
            listOf(Wallpaper, Static) + baseVariants() + builtins() + highContrasts()

        /**
         * Resolves a [ThemePreset] from its [key], returning [Wallpaper] for unknown keys so that
         * a backup-restore from a future app version degrades gracefully instead of crashing.
         */
        fun fromKey(key: String): ThemePreset =
            all().firstOrNull { it.key == key } ?: Wallpaper
    }
}
