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

    companion object {
        const val FILE_NAME = "dev.tally.prefs"
        const val DEFAULT_PRECISION = -1  // -1 = auto
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
    }
}
