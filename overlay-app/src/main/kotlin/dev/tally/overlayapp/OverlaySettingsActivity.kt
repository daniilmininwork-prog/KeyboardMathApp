package dev.tally.overlayapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts [OverlaySettingsFragment] in a toolbar shell. Mirrors :app's SettingsActivity so the
 * back/up affordance behaves identically.
 */
class OverlaySettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
