package dev.tally

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

/**
 * Home screen.
 *
 * On every resume the activity checks whether Tally is the active IME:
 *   - Not active → the setup-required state is shown with a "Set up Tally" button
 *     that re-enters [OnboardingActivity].
 *   - Active → a confirmation state is shown with "Try it again", "Settings",
 *     and "About" buttons.
 *
 * This means there are no dead ends: a user who enabled Tally, landed here, then
 * disabled it will see the setup prompt again on the next resume.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_setup).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        findViewById<Button>(R.id.btn_try_again).setOnClickListener {
            startActivity(OnboardingActivity.tryItIntent(this))
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val active = ImeStateChecker.isTallySelected(this)

        findViewById<TextView>(R.id.main_status_title).text = getString(
            if (active) R.string.main_status_active else R.string.main_status_inactive
        )
        findViewById<TextView>(R.id.main_status_sub).text = getString(
            if (active) R.string.main_status_active_sub else R.string.main_status_inactive_sub
        )

        findViewById<Button>(R.id.btn_setup).visibility =
            if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btn_try_again).visibility =
            if (active) View.VISIBLE else View.GONE
    }
}
