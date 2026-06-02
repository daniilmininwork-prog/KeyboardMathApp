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
