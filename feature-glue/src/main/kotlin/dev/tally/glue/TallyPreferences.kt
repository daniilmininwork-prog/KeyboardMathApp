package dev.tally.glue

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.tally.math.PercentMode

class TallyPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    // Separate master switch for the accessibility overlay surface. Kept distinct from [enabled]
    // (which gates the IME's suggestion strip) so the standalone overlay app can be turned off
    // without silently disabling the keyboard's math suggestions on the same device, and vice versa.
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, v).apply()

    // Stored as a string so that ListPreference (which always writes strings) stays in sync.
    var precision: Int
        get() = prefs.getString(KEY_PRECISION, DEFAULT_PRECISION.toString())?.toIntOrNull() ?: DEFAULT_PRECISION
        set(v) = prefs.edit().putString(KEY_PRECISION, v.toString()).apply()

    var percentMode: PercentMode
        get() {
            val stored = prefs.getString(KEY_PERCENT_MODE, PercentMode.ADDITIVE.name)
                ?: PercentMode.ADDITIVE.name
            return try {
                PercentMode.valueOf(stored)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unrecognised stored PercentMode value '$stored'; falling back to ADDITIVE. " +
                    "This may indicate a backup restore from a newer app version.", e)
                PercentMode.ADDITIVE
            }
        }
        set(v) = prefs.edit().putString(KEY_PERCENT_MODE, v.name).apply()

    var replaceExpression: Boolean
        get() = prefs.getBoolean(KEY_REPLACE_EXPRESSION, false)
        set(v) = prefs.edit().putBoolean(KEY_REPLACE_EXPRESSION, v).apply()

    /**
     * When true the committed text is [MathEngine.exactValue] (full-precision decimal) rather
     * than the locale-formatted [MathEngine.display] string. Off by default; the formatted
     * display is what most users expect.
     */
    var insertExactValue: Boolean
        get() = prefs.getBoolean(KEY_INSERT_EXACT_VALUE, false)
        set(v) = prefs.edit().putBoolean(KEY_INSERT_EXACT_VALUE, v).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    var localeOverride: String
        get() = prefs.getString(KEY_LOCALE_OVERRIDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LOCALE_OVERRIDE, v).apply()

    /**
     * Whether the optional dedicated number row is shown at the top of the alphabetic keyboard.
     *
     * When true an extra row of digit keys (1–9, 0) appears above the QWERTY rows, giving
     * one-tap access to digits without switching to the numeric layer. Persisted so the user's
     * choice survives app restarts.
     */
    var numberRowEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(v) = prefs.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    /**
     * Whether a high-confidence typed word is silently corrected when the user presses Space.
     *
     * On by default — autocorrect is the expected behaviour for a soft keyboard. When false the
     * keyboard never replaces what the user typed, even if a correction is offered in the strip.
     */
    var autocorrectEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOCORRECT, v).apply()

    /**
     * Whether out-of-dictionary words are flagged with a red spell-check underline (Stage 5).
     *
     * On by default, matching the system-keyboard convention. The check is fully on-device —
     * it consults the same bundled English dictionary used for prediction/autocorrect, so it
     * works with no network and never transmits typed text. When false the keyboard never draws
     * the red underline and tapping a word offers no spell corrections. Re-read on field entry so
     * a toggle made while the keyboard was hidden takes effect on the next focus.
     */
    var spellCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPELL_CHECK, true)
        set(v) = prefs.edit().putBoolean(KEY_SPELL_CHECK, v).apply()

    /**
     * Whether committing a word from the suggestion strip auto-appends a trailing space.
     *
     * On by default, matching the Gboard convention that tapping a candidate starts the next
     * word cleanly. Does not affect the literal space produced by pressing the Space key.
     */
    var autoSpaceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SPACE, v).apply()

    /**
     * Whether the shift key auto-capitalises at the start of a sentence.
     *
     * On by default, matching the system-keyboard convention. When false the keyboard never
     * raises shift on its own — neither on field entry nor after ". " — so the user controls
     * every capital. Does not affect a manual shift tap, which always works regardless.
     */
    var autoCapEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAP, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CAP, v).apply()

    /**
     * Whether typing two spaces in quick succession is replaced with ". " (period + space).
     *
     * On by default, matching the Gboard/iOS convention for ending a sentence quickly. The
     * replacement only fires when a word character precedes the first space; double-space after
     * punctuation or whitespace inserts two literal spaces so list/indent typing is unaffected.
     */
    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(v) = prefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, v).apply()

    /**
     * Whether each key's primary long-press alternate is drawn as a small hint glyph in the
     * key's corner (Samsung/Gboard-style keycap hint).
     *
     * Off by default, matching Samsung's stock keyboard, which ships the hint hidden. When true
     * the first [dev.tally.ime.Key.moreKeys] entry of every key that carries alternates is drawn
     * faintly in the upper corner so the long-press character is discoverable without holding.
     */
    var altCharHints: Boolean
        get() = prefs.getBoolean(KEY_ALT_CHAR_HINTS, false)
        set(v) = prefs.edit().putBoolean(KEY_ALT_CHAR_HINTS, v).apply()

    /**
     * The active keyboard theme preset key (matches [dev.tally.design.ThemePreset.key]).
     *
     * Defaults to "wallpaper" so new installs on Android 12+ automatically follow the system
     * palette without any user action. On API < 31 the keyboard ignores the wallpaper key and
     * uses static resource tokens regardless of this value — the UI should make this clear.
     */
    var themePresetKey: String
        get() = prefs.getString(KEY_THEME_PRESET, DEFAULT_THEME_PRESET) ?: DEFAULT_THEME_PRESET
        set(v) = prefs.edit().putString(KEY_THEME_PRESET, v).apply()

    // ── Input timing (Stage 2) ────────────────────────────────────────────────

    /**
     * Backspace key-repeat speed key (matches [dev.tally.keyboard.engine.BackspaceSpeed.name]).
     *
     * Defaults to "NORMAL", which reproduces the previously hardcoded repeat schedule exactly.
     * Stored as the enum's name (a raw String) for the same reason as [formFactorKey]: this
     * module does not depend on keyboard-engine, so the IME layer resolves the name to the
     * enum. Re-read on field entry so a change made while the keyboard was hidden takes effect.
     */
    var backspaceSpeedKey: String
        get() = prefs.getString(KEY_BACKSPACE_SPEED, DEFAULT_BACKSPACE_SPEED) ?: DEFAULT_BACKSPACE_SPEED
        set(v) = prefs.edit().putString(KEY_BACKSPACE_SPEED, v).apply()

    /**
     * Touch-and-hold (long-press) delay key (matches
     * [dev.tally.keyboard.engine.LongPressDelay.name]).
     *
     * Defaults to "MEDIUM" (≈400 ms), matching the value that was previously hardcoded in
     * KeyPlaneView. Stored as the enum's name for the same reason as [backspaceSpeedKey].
     */
    var longPressDelayKey: String
        get() = prefs.getString(KEY_LONG_PRESS_DELAY, DEFAULT_LONG_PRESS_DELAY) ?: DEFAULT_LONG_PRESS_DELAY
        set(v) = prefs.edit().putString(KEY_LONG_PRESS_DELAY, v).apply()

    /**
     * Multiplier applied to the on-key glyph size at draw time (Stage 3).
     *
     * Defaults to 1.0 (the previously hardcoded sizes). Scaling is purely visual — it changes only
     * the rendered glyph size, never the key rects or keyboard footprint — so a larger or smaller
     * font does not shift touch targets. Stored as a string because ListPreference always persists
     * strings; an unparseable value (e.g. from a backup of a future version) falls back to 1.0.
     * Re-read on field entry so a change made while the keyboard was hidden takes effect next focus.
     */
    var keyFontScale: Float
        get() = prefs.getString(KEY_KEY_FONT_SCALE, DEFAULT_KEY_FONT_SCALE)
            ?.toFloatOrNull() ?: DEFAULT_KEY_FONT_SCALE.toFloat()
        set(v) = prefs.edit().putString(KEY_KEY_FONT_SCALE, v.toString()).apply()

    // ── Voice input (T5.2) ───────────────────────────────────────────────────

    /**
     * Whether the opt-in voice input feature is enabled.
     *
     * Off by default. When true, a microphone key appears in the keyboard's bottom row
     * and the OS on-device speech recognizer is invoked on press. The device must grant
     * RECORD_AUDIO permission at runtime before dictation can proceed.
     */
    var voiceInputEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_INPUT, false)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_INPUT, v).apply()

    // ── Multilingual / subtype switcher (T5.1) ───────────────────────────────

    /**
     * The layout-id of the currently active subtype (e.g. "en_US_QWERTY").
     *
     * Persisted so the user's language choice survives service restarts. Defaults to the
     * English QWERTY layout. The value is validated against the known subtype list at
     * load time; an unrecognised value falls back to the first enabled subtype.
     */
    var activeSubtypeId: String
        get() = prefs.getString(KEY_ACTIVE_SUBTYPE, DEFAULT_SUBTYPE_ID) ?: DEFAULT_SUBTYPE_ID
        set(v) = prefs.edit().putString(KEY_ACTIVE_SUBTYPE, v).apply()

    // ── Form-factor (T4.6) ────────────────────────────────────────────────────

    /**
     * Active form-factor mode key (matches [dev.tally.keyboard.engine.FormFactorMode.name]).
     *
     * Defaults to "NORMAL" (full-width anchored keyboard). Changing to ONE_HANDED, SPLIT,
     * or FLOATING applies a pure geometry transform over the same key model — no keyboard
     * logic changes, only placement and sizing.
     */
    var formFactorKey: String
        get() = prefs.getString(KEY_FORM_FACTOR, DEFAULT_FORM_FACTOR) ?: DEFAULT_FORM_FACTOR
        set(v) = prefs.edit().putString(KEY_FORM_FACTOR, v).apply()

    /**
     * Whether the one-handed keyboard is aligned to the right edge.
     *
     * When false (the default), the one-handed keyboard anchors to the left edge. Persisted
     * separately from the mode key so the side can be toggled without changing the mode.
     */
    var oneHandedRight: Boolean
        get() = prefs.getBoolean(KEY_ONE_HANDED_RIGHT, false)
        set(v) = prefs.edit().putBoolean(KEY_ONE_HANDED_RIGHT, v).apply()

    /**
     * Horizontal pixel offset for the floating keyboard from the left edge of the IME window.
     *
     * Clamped by [dev.tally.keyboard.engine.FormFactorTransform.resolve] so the panel
     * stays within the visible viewport — storing out-of-range values here is harmless.
     */
    var floatingOffsetX: Int
        get() = prefs.getInt(KEY_FLOATING_OFFSET_X, 0)
        set(v) = prefs.edit().putInt(KEY_FLOATING_OFFSET_X, v).apply()

    /**
     * Vertical pixel offset for the floating keyboard from the top of the IME window.
     */
    var floatingOffsetY: Int
        get() = prefs.getInt(KEY_FLOATING_OFFSET_Y, 0)
        set(v) = prefs.edit().putInt(KEY_FLOATING_OFFSET_Y, v).apply()

    companion object {
        const val FILE_NAME = "dev.tally.prefs"
        const val DEFAULT_PRECISION = -1  // -1 = auto
        const val DEFAULT_THEME_PRESET = "wallpaper"
        private const val TAG = "TallyPreferences"

        const val KEY_ENABLED            = "enabled"
        const val KEY_OVERLAY_ENABLED    = "overlay_enabled"
        const val KEY_PRECISION          = "precision"
        const val KEY_PERCENT_MODE       = "percent_mode"
        const val KEY_REPLACE_EXPRESSION = "replace_expression"
        const val KEY_INSERT_EXACT_VALUE = "insert_exact_value"
        const val KEY_HAPTICS            = "haptics"
        const val KEY_SOUND              = "sound"
        const val KEY_LOCALE_OVERRIDE    = "locale_override"
        const val KEY_NUMBER_ROW         = "number_row_enabled"
        const val KEY_AUTOCORRECT        = "autocorrect_enabled"
        const val KEY_SPELL_CHECK        = "spell_check_enabled"
        const val KEY_AUTO_SPACE         = "auto_space_enabled"
        const val KEY_AUTO_CAP           = "auto_cap_enabled"
        const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period_enabled"
        const val KEY_ALT_CHAR_HINTS     = "alt_char_hints_enabled"
        const val KEY_THEME_PRESET       = "theme_preset"
        const val KEY_ACTIVE_SUBTYPE     = "active_subtype"
        const val DEFAULT_SUBTYPE_ID     = "en_US_QWERTY"
        const val KEY_FORM_FACTOR        = "form_factor"
        const val KEY_ONE_HANDED_RIGHT   = "one_handed_right"
        const val KEY_FLOATING_OFFSET_X  = "floating_offset_x"
        const val KEY_FLOATING_OFFSET_Y  = "floating_offset_y"
        const val DEFAULT_FORM_FACTOR    = "NORMAL"
        const val KEY_VOICE_INPUT        = "voice_input_enabled"
        const val KEY_BACKSPACE_SPEED    = "backspace_speed"
        const val DEFAULT_BACKSPACE_SPEED = "NORMAL"
        const val KEY_LONG_PRESS_DELAY   = "long_press_delay"
        const val DEFAULT_LONG_PRESS_DELAY = "MEDIUM"
        const val KEY_KEY_FONT_SCALE     = "key_font_scale"
        // Stored as a string to match ListPreference's string-only persistence; "1.0" reproduces
        // the previously hardcoded glyph sizes exactly.
        const val DEFAULT_KEY_FONT_SCALE = "1.0"
    }
}
