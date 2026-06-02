package dev.tally.ime

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [KeyFeedback].
 *
 * Acceptance criteria covered (T1.8):
 *   - performHapticFeedback(KEYBOARD_TAP) fires when hapticsEnabled and not when off.
 *   - AudioManager.playSoundEffect fires when soundEnabled and not when off.
 *   - No VIBRATE permission is present in the manifest.
 *   - isSystemHapticsEnabled() reflects the Settings.System value.
 *   - Both toggles are independent of each other.
 *   - hapticsEnabled and soundEnabled prefs are stored separately in TallyPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyFeedbackTest {

    private lateinit var context: Context
    private lateinit var hostView: View

    // Capture what the collaborators received.
    private var lastHapticView: View? = null
    private var lastHapticConstant: Int? = null
    private var lastSoundEffect: Int? = null

    /** Resets the spy state between test runs. */
    private fun resetSpies() {
        lastHapticView = null
        lastHapticConstant = null
        lastSoundEffect = null
    }

    private fun makeSpyFeedback(
        hapticsEnabled: Boolean = true,
        soundEnabled: Boolean = true,
    ): KeyFeedback = KeyFeedback(
        context       = context,
        hapticsEnabled = hapticsEnabled,
        soundEnabled   = soundEnabled,
        hapticPerformer = { view, constant ->
            lastHapticView     = view
            lastHapticConstant = constant
            true
        },
        soundPlayer = { effectType ->
            lastSoundEffect = effectType
        },
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        hostView = View(context)
        resetSpies()
    }

    // ── Haptics ───────────────────────────────────────────────────────────────

    @Test
    fun onKeyDown_hapticsEnabled_performsKeyboardTap() {
        val feedback = makeSpyFeedback(hapticsEnabled = true, soundEnabled = false)

        feedback.onKeyDown(hostView)

        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, lastHapticConstant)
        assertEquals(hostView, lastHapticView)
    }

    @Test
    fun onKeyDown_hapticsDisabled_noHapticCall() {
        val feedback = makeSpyFeedback(hapticsEnabled = false, soundEnabled = false)

        feedback.onKeyDown(hostView)

        assertNull("Expected no haptic call when hapticsEnabled=false", lastHapticConstant)
    }

    @Test
    fun onKeyDown_hapticsToggleMidSession_respectsCurrentState() {
        val feedback = makeSpyFeedback(hapticsEnabled = true, soundEnabled = false)
        feedback.onKeyDown(hostView)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, lastHapticConstant)

        resetSpies()
        feedback.hapticsEnabled = false
        feedback.onKeyDown(hostView)
        assertNull("After disabling haptics no call expected", lastHapticConstant)
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    @Test
    fun onKeyDown_soundEnabled_playsKeyPressSoundEffect() {
        val feedback = makeSpyFeedback(hapticsEnabled = false, soundEnabled = true)

        feedback.onKeyDown(hostView)

        assertEquals(AudioManager.FX_KEYPRESS_STANDARD, lastSoundEffect)
    }

    @Test
    fun onKeyDown_soundDisabled_noSoundCall() {
        val feedback = makeSpyFeedback(hapticsEnabled = false, soundEnabled = false)

        feedback.onKeyDown(hostView)

        assertNull("Expected no sound when soundEnabled=false", lastSoundEffect)
    }

    @Test
    fun onKeyDown_soundToggleMidSession_respectsCurrentState() {
        val feedback = makeSpyFeedback(hapticsEnabled = false, soundEnabled = true)
        feedback.onKeyDown(hostView)
        assertEquals(AudioManager.FX_KEYPRESS_STANDARD, lastSoundEffect)

        resetSpies()
        feedback.soundEnabled = false
        feedback.onKeyDown(hostView)
        assertNull("After disabling sound no call expected", lastSoundEffect)
    }

    // ── Independence ──────────────────────────────────────────────────────────

    @Test
    fun hapticsAndSound_areIndependent_hapticsOnSoundOff() {
        val feedback = makeSpyFeedback(hapticsEnabled = true, soundEnabled = false)

        feedback.onKeyDown(hostView)

        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, lastHapticConstant)
        assertNull("Sound must not fire when soundEnabled=false", lastSoundEffect)
    }

    @Test
    fun hapticsAndSound_areIndependent_hapticsOffSoundOn() {
        val feedback = makeSpyFeedback(hapticsEnabled = false, soundEnabled = true)

        feedback.onKeyDown(hostView)

        assertNull("Haptic must not fire when hapticsEnabled=false", lastHapticConstant)
        assertEquals(AudioManager.FX_KEYPRESS_STANDARD, lastSoundEffect)
    }

    // ── System settings ───────────────────────────────────────────────────────

    @Test
    fun isSystemHapticsEnabled_defaultsToTrue() {
        val feedback = makeSpyFeedback()

        // Robolectric's ContentProvider returns the Settings default (1) when absent.
        assertTrue(feedback.isSystemHapticsEnabled())
    }

    @Test
    fun isSystemHapticsEnabled_falseWhenSettingIsZero() {
        @Suppress("DEPRECATION")
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            0,
        )
        val feedback = makeSpyFeedback()

        assertFalse(feedback.isSystemHapticsEnabled())

        // Restore the setting so it does not bleed into other tests.
        @Suppress("DEPRECATION")
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
    }

    // ── Manifest — no VIBRATE permission ─────────────────────────────────────

    @Test
    fun manifest_doesNotDeclareVibratePermission() {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "VIBRATE permission must not be requested. Found: $permissions",
            permissions.any { it == "android.permission.VIBRATE" },
        )
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Test
    fun tallyPreferences_hapticsAndSound_areStoredSeparately() {
        val prefs = dev.tally.glue.TallyPreferences(context)

        prefs.hapticsEnabled = false
        prefs.soundEnabled = true

        assertFalse(prefs.hapticsEnabled)
        assertTrue(prefs.soundEnabled)

        prefs.hapticsEnabled = true
        prefs.soundEnabled = false

        assertTrue(prefs.hapticsEnabled)
        assertFalse(prefs.soundEnabled)
    }

    @Test
    fun tallyPreferences_soundEnabled_defaultsToTrue() {
        // Use a fresh SharedPreferences namespace so no previous test writes affect this.
        val freshPrefs = dev.tally.glue.TallyPreferences(context)
        // Default value before any explicit set must be true (ship with feedback on).
        assertTrue(freshPrefs.soundEnabled)
    }
}
