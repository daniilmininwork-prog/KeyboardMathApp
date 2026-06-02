package dev.tally.design

import androidx.annotation.ColorInt

/**
 * The set of keyboard theme options exposed to the user.
 *
 * Each preset maps to a [DynamicColorScheme.Scheme] or to the sentinel null (static tokens only).
 * The IME reads the active preset from [TallyThemeManager] and applies the resulting scheme to
 * every [KeyTheme] instance via [KeyTheme.applyDynamicColors].
 *
 * ## Adding presets
 *
 * Extend [Builtin] with a new data object and provide its seed colors. The IME and settings
 * screen automatically pick up the new entry through [ThemePreset.builtins()].
 *
 * ## Serialization
 *
 * [key] is the stable string stored in SharedPreferences. Never change an existing key — doing so
 * silently resets every user's saved choice to [Wallpaper].
 */
sealed class ThemePreset(val key: String) {

    /**
     * Derive the tonal palette from the current system wallpaper (Android 12+).
     * On older versions the keyboard falls back to static resource tokens.
     */
    data object Wallpaper : ThemePreset("wallpaper")

    /**
     * Ignore the wallpaper and use the static design-system tokens unchanged.
     * This is the safe, always-correct fallback and the default on API < 31.
     */
    data object Static : ThemePreset("static")

    /**
     * A built-in preset with a fixed primary seed color.
     *
     * The seed drives [KeyTheme.applyDynamicColors] in exactly the same way as a wallpaper-derived
     * scheme, so the blend/alpha logic is identical — only the hue origin differs.
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

    companion object {

        /** Every [Builtin] preset in display order; does not include [Wallpaper] or [Static]. */
        fun builtins(): List<Builtin> = Builtin.all()

        /**
         * Resolves a [ThemePreset] from its [key], returning [Wallpaper] for unknown keys so that
         * a backup-restore from a future app version degrades gracefully instead of crashing.
         */
        fun fromKey(key: String): ThemePreset = when (key) {
            Wallpaper.key -> Wallpaper
            Static.key    -> Static
            else -> Builtin.all().firstOrNull { it.key == key } ?: Wallpaper
        }
    }
}
