package dev.tally

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ApplicationProvider
import dev.tally.glue.TallyPreferences
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that all user-facing toggles introduced in M1/M2 are present in the settings
 * preference screen, so that users have access to every persisted option.
 *
 * Tests inflate the preferences XML directly — no Activity host is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsFragmentTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * Inflate the preferences XML and return the resulting [PreferenceFragmentCompat] with
     * preferences ready to query.
     */
    private fun createFragment(): SettingsFragment {
        val fragment = SettingsFragment()
        // Bootstrap the preferences manager the same way the fragment does at runtime.
        // Robolectric provides a functional PreferenceManager via ApplicationProvider.
        val prefManager = androidx.preference.PreferenceManager(context)
        prefManager.sharedPreferencesName = TallyPreferences.FILE_NAME
        prefManager.inflateFromResource(context, R.xml.preferences, null)
        return fragment
    }

    /**
     * Parse the preferences XML independently so tests do not require a running activity.
     */
    private fun inflatedPreferences(): androidx.preference.PreferenceScreen {
        val mgr = androidx.preference.PreferenceManager(context)
        mgr.sharedPreferencesName = TallyPreferences.FILE_NAME
        return mgr.inflateFromResource(context, R.xml.preferences, null)
    }

    private fun findPref(key: String): Preference? =
        inflatedPreferences().findPreference(key)

    // ── Core math preferences ─────────────────────────────────────────────────

    @Test
    fun settings_hasMathEnabledToggle() {
        assertNotNull("Missing 'enabled' preference", findPref(TallyPreferences.KEY_ENABLED))
    }

    @Test
    fun settings_hasPrecisionList() {
        assertNotNull("Missing 'precision' preference", findPref(TallyPreferences.KEY_PRECISION))
    }

    @Test
    fun settings_hasPercentModeList() {
        assertNotNull("Missing 'percent_mode' preference", findPref(TallyPreferences.KEY_PERCENT_MODE))
    }

    @Test
    fun settings_hasReplaceExpressionToggle() {
        assertNotNull(
            "Missing 'replace_expression' preference",
            findPref(TallyPreferences.KEY_REPLACE_EXPRESSION),
        )
    }

    // ── New toggles: added with M1/M2 keyboard work ───────────────────────────

    @Test
    fun settings_hasHapticsToggle() {
        assertNotNull("Missing 'haptics' preference", findPref(TallyPreferences.KEY_HAPTICS))
    }

    @Test
    fun settings_hasSoundToggle() {
        assertNotNull(
            "Missing 'sound' preference — T1.8 key sounds toggle absent from settings UI",
            findPref(TallyPreferences.KEY_SOUND),
        )
    }

    @Test
    fun settings_hasNumberRowToggle() {
        assertNotNull(
            "Missing 'number_row_enabled' preference — T1.10 number row toggle absent from settings UI",
            findPref(TallyPreferences.KEY_NUMBER_ROW),
        )
    }

    @Test
    fun settings_hasInsertExactValueToggle() {
        assertNotNull(
            "Missing 'insert_exact_value' preference — insert exact value toggle absent from settings UI",
            findPref(TallyPreferences.KEY_INSERT_EXACT_VALUE),
        )
    }

    @Test
    fun settings_hasAutocorrectToggle() {
        assertNotNull(
            "Missing 'autocorrect_enabled' preference — PHASE 1a autocorrect toggle absent from settings UI",
            findPref(TallyPreferences.KEY_AUTOCORRECT),
        )
    }

    @Test
    fun settings_hasAutoSpaceToggle() {
        assertNotNull(
            "Missing 'auto_space_enabled' preference — PHASE 1a auto-space toggle absent from settings UI",
            findPref(TallyPreferences.KEY_AUTO_SPACE),
        )
    }

    @Test
    fun settings_hasAutoCapToggle() {
        assertNotNull(
            "Missing 'auto_cap_enabled' preference — PHASE 1b auto-cap toggle absent from settings UI",
            findPref(TallyPreferences.KEY_AUTO_CAP),
        )
    }

    @Test
    fun settings_hasDoubleSpacePeriodToggle() {
        assertNotNull(
            "Missing 'double_space_period_enabled' preference — PHASE 1b toggle absent from settings UI",
            findPref(TallyPreferences.KEY_DOUBLE_SPACE_PERIOD),
        )
    }

    @Test
    fun settings_hasAltCharHintsToggle() {
        assertNotNull(
            "Missing 'alt_char_hints_enabled' preference — Stage 1 keycap-hint toggle absent from settings UI",
            findPref(TallyPreferences.KEY_ALT_CHAR_HINTS),
        )
    }

    @Test
    fun settings_hasKeyFontScaleList() {
        assertNotNull(
            "Missing 'key_font_scale' preference — Stage 3 key text-size control absent from settings UI",
            findPref(TallyPreferences.KEY_KEY_FONT_SCALE),
        )
    }

    @Test
    fun settings_hasThemePresetList() {
        assertNotNull(
            "Missing 'theme_preset' preference — Stage 4 theme picker absent from settings UI",
            findPref(TallyPreferences.KEY_THEME_PRESET),
        )
    }

    @Test
    fun themePresetList_entryValues_matchThemePresetKeys() {
        // The ListPreference values must round-trip through ThemePreset.fromKey() — a mismatch would
        // silently fall back to Wallpaper at runtime, so guard the XML against drift.
        val pref = findPref(TallyPreferences.KEY_THEME_PRESET)
                as androidx.preference.ListPreference
        val values = pref.entryValues.map { it.toString() }.toSet()
        val keys = dev.tally.design.ThemePreset.all().map { it.key }.toSet()
        org.junit.Assert.assertEquals(
            "theme_preset entryValues must exactly match ThemePreset.all() keys", keys, values,
        )
    }
}
