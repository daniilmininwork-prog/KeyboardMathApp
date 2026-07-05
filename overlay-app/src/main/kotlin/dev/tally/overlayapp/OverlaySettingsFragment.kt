package dev.tally.overlayapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.tally.glue.TallyPreferences
import dev.tally.overlay.OverlayConsentActivity
import dev.tally.overlay.OverlayPermissionState

/**
 * Settings for overlay mode only.
 *
 * The fragment writes into [TallyPreferences.FILE_NAME] — the exact same shared store the
 * accessibility service ([dev.tally.overlay.TallyOverlayService]) reads — so a change here takes
 * effect on the next evaluation with no restart. Only the three overlay-relevant keys are shown
 * (enabled / precision / percent_mode); the keys are wired to TallyPreferences.KEY_* so they
 * stay in lock-step with the service.
 */
class OverlaySettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Share the IME/overlay backing store so the service sees these writes immediately.
        preferenceManager.sharedPreferencesName = TallyPreferences.FILE_NAME

        setPreferencesFromResource(R.xml.overlay_preferences, rootKey)

        // Show the human-readable entry label (e.g. "2 decimal places") rather than the raw value.
        findPreference<ListPreference>(TallyPreferences.KEY_PRECISION)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>(TallyPreferences.KEY_PERCENT_MODE)
            ?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        findPreference<Preference>(KEY_OVERLAY_SERVICE)?.setOnPreferenceClickListener {
            try {
                startActivity(Intent(requireContext(), OverlayConsentActivity::class.java))
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "OverlayConsentActivity unavailable; cannot open accessibility flow.", e)
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceSummary()
    }

    /** Reflect the live accessibility-service state in the jump-off preference's summary. */
    private fun updateServiceSummary() {
        val enabled = OverlayPermissionState.isAccessibilityServiceEnabled(requireContext())
        val summaryRes = if (enabled) {
            R.string.overlay_app_status_on_title
        } else {
            R.string.overlay_app_status_off_title
        }
        findPreference<Preference>(KEY_OVERLAY_SERVICE)?.setSummary(summaryRes)
    }

    private companion object {
        private const val TAG = "OverlayApp"
        private const val KEY_OVERLAY_SERVICE = "overlay_service"
    }
}
