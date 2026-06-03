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

    // Optional full-palette override injected via applyPalette(). When non-null it wins over BOTH
    // the static resource tokens and the dynamic scheme for every token, and is the only source of
    // the per-key border. Used by the explicit Light/Dark base variants and the high-contrast
    // presets, which must look identical regardless of the system day/night qualifier.
    private var palette: KeyPalette? = null

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
     * A scheme does NOT clear an active [applyPalette] override — a full palette is the stronger
     * signal (the user explicitly forced a fixed/high-contrast look) and stays in force until it is
     * cleared with `applyPalette(null)`. Callers that switch from a palette preset to a dynamic
     * preset must clear the palette first; [TallyThemeManager] does this for us.
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

    /**
     * Applies a full [KeyPalette] override (or null to clear it).
     *
     * While a palette is set, every token below returns the palette value and [drawKeyBorders]
     * reflects the palette's border flag, ignoring both the static day/night resources and any
     * dynamic scheme. This is how the explicit base variants pin a brightness and how the
     * high-contrast presets force a legible, bordered look that overrides the active theme.
     *
     * Pass null to revert to the dynamic-scheme / static-resource behaviour.
     *
     * Must be called on the main thread; takes effect on the next [onDraw].
     */
    fun applyPalette(palette: KeyPalette?) {
        this.palette = palette
    }

    /**
     * Whether views should stroke a border around each key face.
     *
     * True only while a high-contrast (border-on) [KeyPalette] is active; the border colour is then
     * [keyBorder]. Base variants and the dynamic/static paths leave this false (flat faces).
     */
    val drawKeyBorders: Boolean
        get() = palette?.drawBorder == true

    /**
     * Stroke colour for the per-key border. Only meaningful when [drawKeyBorders] is true; falls
     * back to [keyText] so a reader that ignores the flag still gets an on-theme colour.
     */
    @get:ColorInt
    val keyBorder: Int
        get() = palette?.keyBorder ?: keyText

    // ── Keyboard background ───────────────────────────────────────────────────

    @get:ColorInt
    val keyboardBg: Int
        get() = palette?.keyboardBg ?: context.getColor(R.color.ds_keyboard_bg)

    // ── Key face ──────────────────────────────────────────────────────────────

    @get:ColorInt
    val keyBg: Int
        get() = palette?.keyBg ?: context.getColor(R.color.ds_key_bg)

    @get:ColorInt
    val keySpecialBg: Int
        get() = palette?.keySpecialBg ?: context.getColor(R.color.ds_key_special_bg)

    @get:ColorInt
    val keyPressedBg: Int
        get() = palette?.keyPressedBg ?: dynamicKeyPressedBg ?: context.getColor(R.color.ds_key_pressed_bg)

    @get:ColorInt
    val keyText: Int
        get() = palette?.keyText ?: context.getColor(R.color.ds_key_text)

    // ── Suggestion strip ──────────────────────────────────────────────────────

    @get:ColorInt
    val suggestionStripBg: Int
        get() = palette?.suggestionStripBg ?: context.getColor(R.color.ds_suggestion_strip_bg)

    // ── Chip ──────────────────────────────────────────────────────────────────

    @get:ColorInt
    val chipBg: Int
        get() = palette?.chipBg ?: dynamicChipBg ?: context.getColor(R.color.ds_chip_bg)

    @get:ColorInt
    val chipText: Int
        get() = palette?.chipText ?: context.getColor(R.color.ds_chip_text)

    // ── Key-preview popup ─────────────────────────────────────────────────────

    @get:ColorInt
    val keyPreviewBg: Int
        get() = palette?.keyPreviewBg ?: context.getColor(R.color.ds_key_preview_bg)

    // ── Long-press alternate tray ─────────────────────────────────────────────

    @get:ColorInt
    val longPressPopupBg: Int
        get() = palette?.longPressPopupBg ?: context.getColor(R.color.ds_long_press_popup_bg)

    @get:ColorInt
    val longPressPopupSelectedBg: Int
        get() = palette?.longPressPopupSelectedBg ?: context.getColor(R.color.ds_long_press_popup_selected_bg)

    // ── Private helpers ───────────────────────────────────────────────────────

    // Returns the RGB channels of [color] with the given [alpha] (0..255) forced in.
    private fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int =
        (color and 0x00_FF_FF_FF) or (alpha.coerceIn(0, 255) shl 24)
}
