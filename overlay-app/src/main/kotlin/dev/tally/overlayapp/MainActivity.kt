package dev.tally.overlayapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import dev.tally.overlay.OverlayConsentActivity
import dev.tally.overlay.OverlayPermissionState

/**
 * Launcher screen for the decoupled Tally Overlay app.
 *
 * On every resume it re-reads the live accessibility-service state via
 * [OverlayPermissionState.isAccessibilityServiceEnabled] (the real detector that lives in
 * :overlay — not reimplemented here) and renders the matching status copy from
 * [OverlayAppStatus]. This means there are no dead ends: a user who enabled the overlay, came
 * back here, then disabled it sees the "off" state again on the next resume.
 *
 * The primary button always routes to [OverlayConsentActivity] (which deep-links into system
 * Accessibility settings); the secondary button opens the overlay-only settings screen.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            openConsentFlow()
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, OverlaySettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = OverlayPermissionState.isAccessibilityServiceEnabled(this)
        val presentation = OverlayAppStatus.presentationFor(enabled)

        val title = findViewById<TextView>(R.id.overlay_status_title)
        title.setText(presentation.titleRes)
        // Spoken status for TalkBack — the live region (set in the layout) announces it on change.
        title.contentDescription = getString(presentation.statusContentDescRes)

        findViewById<TextView>(R.id.overlay_status_sub).setText(presentation.subtitleRes)
        findViewById<Button>(R.id.btn_enable).setText(presentation.ctaLabelRes)
    }

    /**
     * Launches the existing consent activity. Wrapped in a guard so a missing/disabled component
     * (e.g. an unusual OEM build) surfaces as a logged no-op instead of crashing the launcher.
     */
    private fun openConsentFlow() {
        try {
            startActivity(Intent(this, OverlayConsentActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "OverlayConsentActivity unavailable; cannot start consent flow.", e)
        }
    }

    private companion object {
        private const val TAG = "OverlayApp"
    }
}
