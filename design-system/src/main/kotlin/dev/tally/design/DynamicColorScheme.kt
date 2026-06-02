package dev.tally.design

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt

/**
 * Platform-native dynamic color scheme for the keyboard.
 *
 * On Android 12+ (API 31) the system exposes a tonal palette derived from the wallpaper via
 * [WallpaperColors]. This class reads those palette seeds and maps them to the keyboard's
 * color tokens, providing the "Material You / dynamic color" groundwork described in the spec
 * (03 §7.1) without introducing a Material library dependency into the keyboard process.
 *
 * An IME runs as a Service, not an Activity, so `DynamicColors.applyToActivityIfAvailable`
 * cannot be used. Instead, we extract the primary/secondary/neutral seed colors from the
 * system wallpaper manager and let callers decide how to blend them with the static token
 * fallback (see [KeyTheme.applyDynamicColors]).
 *
 * On API < 31 or when the wallpaper manager returns null, [resolve] returns null and callers
 * fall back to the static [KeyTheme] tokens unchanged.
 *
 * ## Usage
 *
 * ```kotlin
 * // In onCreateInputView() or whenever the wallpaper might have changed:
 * val scheme = DynamicColorScheme.resolve(context)
 * keyTheme.applyDynamicColors(scheme)
 * ```
 *
 * No network access is involved; wallpaper color extraction is a local OS call.
 */
object DynamicColorScheme {

    /**
     * Attempts to resolve a [Scheme] from the current system wallpaper on API 31+.
     *
     * @return a [Scheme] with the primary/secondary/neutral seed colors, or null when
     *         dynamic color is unavailable (API < 31 or permission not granted).
     */
    fun resolve(context: Context): Scheme? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            resolveApi31(context)
        } catch (_: SecurityException) {
            // READ_WALLPAPER_COLORS not granted or wallpaper service unavailable.
            null
        } catch (_: Exception) {
            null
        }
    }

    // Kept in a separate function so the API-31 branch is only loaded on API 31+ devices.
    // @RequiresApi would suppress the lint warning; we guard with the runtime check in resolve().
    @Suppress("NewApi")
    private fun resolveApi31(context: Context): Scheme? {
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val colors: WallpaperColors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
        return Scheme(
            primarySeed   = colors.primaryColor.toArgb(),
            secondarySeed = colors.secondaryColor?.toArgb() ?: colors.primaryColor.toArgb(),
            neutralSeed   = colors.tertiaryColor?.toArgb()  ?: colors.primaryColor.toArgb(),
        )
    }

    /**
     * The three palette seed colors extracted from the system wallpaper.
     *
     * These are raw ARGB ints — fully-saturated "seed" hues. Callers that need exact tonal
     * shades should blend or shade them according to their surface contrast requirements.
     * The keyboard currently uses them only as a signal to shift the chip and key-face accent
     * towards the wallpaper hue while keeping the static neutral backgrounds.
     */
    data class Scheme(
        @ColorInt val primarySeed: Int,
        @ColorInt val secondarySeed: Int,
        @ColorInt val neutralSeed: Int,
    )
}
