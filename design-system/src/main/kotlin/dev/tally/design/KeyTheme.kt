package dev.tally.design

import android.content.Context
import androidx.annotation.ColorInt

/**
 * Keyboard theme token provider.
 *
 * Colors are resolved from the [Context] on each property access rather than captured at
 * construction. This means a view that holds a [KeyTheme] instance and reads its tokens
 * inside [android.view.View.onDraw] will automatically pick up the correct light or dark
 * values after the system configuration changes — without requiring a view recreate.
 *
 * All color resources that live in `values/` and `values-night/` are covered here so every
 * keyboard surface (key plane, suggestion strip, popups) and the overlay chip share a single
 * source of truth for color naming.
 *
 * ## Dynamic color (Material You / Android 12+)
 *
 * Call [applyDynamicColors] with a [DynamicColorScheme.Scheme] to shift the chip accent and
 * key-face hue towards the wallpaper palette. The dynamic overrides are stored in-instance and
 * take effect on the next property access, so any views reading tokens on `onDraw` will
 * automatically reflect the palette without a view recreate.
 *
 * When no scheme is applied (the default, or on API < 31), the static resource tokens are
 * returned unchanged.
 *
 * Usage inside a custom view:
 * ```kotlin
 * private val theme = KeyTheme(context)
 *
 * override fun onDraw(canvas: Canvas) {
 *     bgPaint.color = theme.keyboardBg
 *     // … draw …
 * }
 * ```
 *
 * Dimensions and motion durations are **not** included here; those are stable at the resource
 * level and safe to read once. Only colors, which vary with the night/day configuration qualifier,
 * are in this class.
 */
class KeyTheme(private val context: Context) {

    // Optional dynamic overrides injected via applyDynamicColors(). Null = use static tokens.
    @ColorInt private var dynamicChipBg: Int? = null
    @ColorInt private var dynamicKeyPressedBg: Int? = null

    // ── Dynamic color injection ───────────────────────────────────────────────

    /**
     * Applies a dynamic color [scheme] to the theme.
     *
     * After this call, [chipBg] and [keyPressedBg] return colors blended toward the wallpaper
     * primary hue instead of the static resource values. All other tokens remain unchanged so
     * neutral surfaces (keyboard background, key background, strip) are unaffected.
     *
     * Pass null to reset to static tokens (e.g. when dynamic color becomes unavailable).
     *
     * Must be called on the main thread. The change takes effect on the next [onDraw] pass in
     * any view that reads these tokens there.
     */
    fun applyDynamicColors(scheme: DynamicColorScheme.Scheme?) {
        if (scheme == null) {
            dynamicChipBg      = null
            dynamicKeyPressedBg = null
            return
        }
        // Blend the wallpaper primary seed toward a keyboard-appropriate opacity/brightness.
        // Full-saturation seeds are intentionally softened so the keyboard stays neutral.
        dynamicChipBg       = blendWithAlpha(scheme.primarySeed, alpha = 0xCC)
        dynamicKeyPressedBg = blendWithAlpha(scheme.primarySeed, alpha = 0x66)
    }

    // ── Keyboard background ───────────────────────────────────────────────────

    @get:ColorInt
    val keyboardBg: Int
        get() = context.getColor(R.color.ds_keyboard_bg)

    // ── Key face ──────────────────────────────────────────────────────────────

    @get:ColorInt
    val keyBg: Int
        get() = context.getColor(R.color.ds_key_bg)

    @get:ColorInt
    val keySpecialBg: Int
        get() = context.getColor(R.color.ds_key_special_bg)

    @get:ColorInt
    val keyPressedBg: Int
        get() = dynamicKeyPressedBg ?: context.getColor(R.color.ds_key_pressed_bg)

    @get:ColorInt
    val keyText: Int
        get() = context.getColor(R.color.ds_key_text)

    // ── Suggestion strip ──────────────────────────────────────────────────────

    @get:ColorInt
    val suggestionStripBg: Int
        get() = context.getColor(R.color.ds_suggestion_strip_bg)

    // ── Chip ──────────────────────────────────────────────────────────────────

    @get:ColorInt
    val chipBg: Int
        get() = dynamicChipBg ?: context.getColor(R.color.ds_chip_bg)

    @get:ColorInt
    val chipText: Int
        get() = context.getColor(R.color.ds_chip_text)

    // ── Key-preview popup ─────────────────────────────────────────────────────

    @get:ColorInt
    val keyPreviewBg: Int
        get() = context.getColor(R.color.ds_key_preview_bg)

    // ── Long-press alternate tray ─────────────────────────────────────────────

    @get:ColorInt
    val longPressPopupBg: Int
        get() = context.getColor(R.color.ds_long_press_popup_bg)

    @get:ColorInt
    val longPressPopupSelectedBg: Int
        get() = context.getColor(R.color.ds_long_press_popup_selected_bg)

    // ── Private helpers ───────────────────────────────────────────────────────

    // Returns the RGB channels of [color] with the given [alpha] (0..255) forced in.
    private fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int =
        (color and 0x00_FF_FF_FF) or (alpha.coerceIn(0, 255) shl 24)
}
