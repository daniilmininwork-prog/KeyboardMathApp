package dev.tally

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.tally.glue.TallyPreferences
import dev.tally.overlay.OverlayConsentActivity
import dev.tally.overlay.OverlayPermissionState

/**
 * Displays and persists Tally's user-facing settings.
 *
 * Preferences are stored in [TallyPreferences.FILE_NAME] so that
 * [dev.tally.ime.TallyInputMethodService] and the settings screen share the same backing store.
 *
 * All changes are applied on the next keystroke after the user returns to the keyboard —
 * no restart is required.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TallyPreferences.FILE_NAME

        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Show human-readable precision label rather than raw int.
        findPreference<ListPreference>(TallyPreferences.KEY_PRECISION)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<ListPreference>(TallyPreferences.KEY_PERCENT_MODE)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<ListPreference>(TallyPreferences.KEY_FORM_FACTOR)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // Show the chosen Slow/Normal/Fast and Short/Medium/Long labels as the summary.
        findPreference<ListPreference>(TallyPreferences.KEY_BACKSPACE_SPEED)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<ListPreference>(TallyPreferences.KEY_LONG_PRESS_DELAY)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // Show the chosen Small/Default/Large/Extra-large label as the summary.
        findPreference<ListPreference>(TallyPreferences.KEY_KEY_FONT_SCALE)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // Show the chosen theme label (e.g. "High contrast: white on black") as the summary.
        findPreference<ListPreference>(TallyPreferences.KEY_THEME_PRESET)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<Preference>("overlay")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), OverlayConsentActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlaySummary()
    }

    private fun updateOverlaySummary() {
        val enabled = OverlayPermissionState.isAccessibilityServiceEnabled(requireContext())
        val summaryRes = if (enabled) {
            R.string.pref_overlay_summary_enabled
        } else {
            R.string.pref_overlay_summary_disabled
        }
        findPreference<Preference>("overlay")?.setSummary(summaryRes)
    }
}
