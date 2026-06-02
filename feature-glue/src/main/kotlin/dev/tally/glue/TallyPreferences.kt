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
     * The active keyboard theme preset key (matches [dev.tally.design.ThemePreset.key]).
     *
     * Defaults to "wallpaper" so new installs on Android 12+ automatically follow the system
     * palette without any user action. On API < 31 the keyboard ignores the wallpaper key and
     * uses static resource tokens regardless of this value — the UI should make this clear.
     */
    var themePresetKey: String
        get() = prefs.getString(KEY_THEME_PRESET, DEFAULT_THEME_PRESET) ?: DEFAULT_THEME_PRESET
        set(v) = prefs.edit().putString(KEY_THEME_PRESET, v).apply()

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
        const val KEY_PRECISION          = "precision"
        const val KEY_PERCENT_MODE       = "percent_mode"
        const val KEY_REPLACE_EXPRESSION = "replace_expression"
        const val KEY_INSERT_EXACT_VALUE = "insert_exact_value"
        const val KEY_HAPTICS            = "haptics"
        const val KEY_SOUND              = "sound"
        const val KEY_LOCALE_OVERRIDE    = "locale_override"
        const val KEY_NUMBER_ROW         = "number_row_enabled"
        const val KEY_THEME_PRESET       = "theme_preset"
        const val KEY_ACTIVE_SUBTYPE     = "active_subtype"
        const val DEFAULT_SUBTYPE_ID     = "en_US_QWERTY"
        const val KEY_FORM_FACTOR        = "form_factor"
        const val KEY_ONE_HANDED_RIGHT   = "one_handed_right"
        const val KEY_FLOATING_OFFSET_X  = "floating_offset_x"
        const val KEY_FLOATING_OFFSET_Y  = "floating_offset_y"
        const val DEFAULT_FORM_FACTOR    = "NORMAL"
        const val KEY_VOICE_INPUT        = "voice_input_enabled"
    }
}
