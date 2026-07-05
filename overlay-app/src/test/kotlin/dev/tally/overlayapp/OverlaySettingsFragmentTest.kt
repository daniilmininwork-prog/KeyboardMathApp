package dev.tally.overlayapp

import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import dev.tally.glue.TallyPreferences
import dev.tally.math.PercentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the overlay settings screen exposes exactly the overlay-relevant preferences and
 * that writes round-trip through the real [TallyPreferences] API into the same shared store the
 * accessibility service reads.
 *
 * Preferences XML is inflated directly (no Activity host needed), mirroring the :app test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlaySettingsFragmentTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun inflatedPreferences(): PreferenceScreen {
        val mgr = PreferenceManager(context)
        mgr.sharedPreferencesName = TallyPreferences.FILE_NAME
        return mgr.inflateFromResource(context, R.xml.overlay_preferences, null)
    }

    private fun findPref(key: String): Preference? = inflatedPreferences().findPreference(key)

    // ── Exposed: the three overlay-relevant fields ────────────────────────────

    @Test
    fun exposes_overlayEnabled() {
        assertNotNull("Missing 'overlay_enabled' preference", findPref(TallyPreferences.KEY_OVERLAY_ENABLED))
    }

    @Test
    fun doesNotExpose_imeEnabledKey() {
        // The overlay's master switch must NOT write the IME's KEY_ENABLED, or toggling the overlay
        // would silently disable the keyboard's math suggestions on the same device.
        assertNull(
            "Overlay settings must not bind the IME's 'enabled' key",
            findPref(TallyPreferences.KEY_ENABLED),
        )
    }

    @Test
    fun exposes_precision() {
        assertNotNull("Missing 'precision' preference", findPref(TallyPreferences.KEY_PRECISION))
    }

    @Test
    fun exposes_percentMode() {
        assertNotNull("Missing 'percent_mode' preference", findPref(TallyPreferences.KEY_PERCENT_MODE))
    }

    // ── NOT exposed: IME-only fields the overlay ignores ──────────────────────

    @Test
    fun doesNotExpose_imeOnlyFields() {
        for (imeOnlyKey in listOf(
            TallyPreferences.KEY_HAPTICS,
            TallyPreferences.KEY_AUTOCORRECT,
            TallyPreferences.KEY_FORM_FACTOR,
            TallyPreferences.KEY_NUMBER_ROW,
            TallyPreferences.KEY_THEME_PRESET,
        )) {
            assertNull(
                "Overlay settings must not expose IME-only key '$imeOnlyKey'",
                findPref(imeOnlyKey),
            )
        }
    }

    // ── Persistence contract: writes round-trip via the real TallyPreferences API ──

    @Test
    fun precisionWrite_isReadableViaTallyPreferences() {
        // Simulate the ListPreference persisting "2" into the shared store.
        context.getSharedPreferences(TallyPreferences.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(TallyPreferences.KEY_PRECISION, "2").commit()

        assertEquals(2, TallyPreferences(context).precision)
    }

    @Test
    fun percentModeWrite_isReadableViaTallyPreferences() {
        context.getSharedPreferences(TallyPreferences.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(TallyPreferences.KEY_PERCENT_MODE, PercentMode.DIRECT.name).commit()

        assertEquals(PercentMode.DIRECT, TallyPreferences(context).percentMode)
    }

    @Test
    fun overlayEnabledWrite_isReadableViaTallyPreferences() {
        context.getSharedPreferences(TallyPreferences.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(TallyPreferences.KEY_OVERLAY_ENABLED, false).commit()

        assertEquals(false, TallyPreferences(context).overlayEnabled)
    }

    @Test
    fun percentModeValues_inXml_matchEnumNames() {
        // The ListPreference entryValues must be valid PercentMode names, else a selection would
        // store a string TallyPreferences.percentMode cannot parse (it falls back to ADDITIVE).
        val values = context.resources.getStringArray(R.array.overlay_app_percent_mode_values)
        for (v in values) {
            // Throws IllegalArgumentException if the XML drifts from the enum — that is the assertion.
            PercentMode.valueOf(v)
        }
        assertEquals(PercentMode.entries.size, values.size)
    }
}
