package dev.tally.overlay

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Guides the user through enabling the Tally Math Overlay accessibility service.
 *
 * The activity is intentionally minimal — one permission, one button, clear language.
 * It detects service state on [onResume] so it auto-updates when the user returns from
 * the system Accessibility settings screen.
 *
 * Declining: the user can simply leave or press Done while the service is still disabled.
 * The keyboard (IME) mode remains fully functional without this service.
 */
class OverlayConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_consent)

        findViewById<Button>(R.id.btn_open_a11y_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Done is enabled only once the service is active, but the user may dismiss anyway.
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = OverlayPermissionState.isAccessibilityServiceEnabled(this)
        val statusId = if (enabled) R.string.overlay_status_enabled else R.string.overlay_status_disabled
        findViewById<TextView>(R.id.overlay_status).setText(statusId)
        // Enable the Done button once the service is on so the user knows it worked,
        // but don't block dismissal — they can still tap Done to go back without enabling.
        findViewById<Button>(R.id.btn_done).isEnabled = true
    }
}
