package dev.tally.ime

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Delivers haptic and audio feedback on each key press.
 *
 * Both feedback types are independently toggleable via user preferences. Both also honour
 * the device-level system switches:
 *
 *   Haptics — [View.performHapticFeedback] with [HapticFeedbackConstants.KEYBOARD_TAP]
 *   already obeys the platform's haptic-feedback system setting (Settings.System
 *   HAPTIC_FEEDBACK_ENABLED) unless [HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING]
 *   is passed. We never pass that flag, so a user who has disabled "touch vibration" in
 *   system settings gets no vibration regardless of the in-keyboard toggle.
 *
 *   Sound — [AudioManager.playSoundEffect] honours the current ringer mode. In silent /
 *   vibrate mode the framework suppresses the effect even if our user toggle is on.
 *
 * No VIBRATE permission is requested or required; [performHapticFeedback] is a view-level
 * API that does not need the permission when the global setting is respected.
 *
 * The [hapticPerformer] and [soundPlayer] parameters exist solely for testing — production
 * code always uses the defaults that delegate to the real platform APIs.
 */
internal class KeyFeedback(
    private val context: Context,
    /** Whether the user has enabled in-keyboard haptic feedback. */
    var hapticsEnabled: Boolean = true,
    /** Whether the user has enabled in-keyboard key-click sounds. */
    var soundEnabled: Boolean = true,
    /**
     * Performs haptic feedback on the host view.
     * Overridable in tests to avoid depending on Robolectric shadow internals.
     */
    private val hapticPerformer: (View, Int) -> Boolean = { view, constant ->
        view.performHapticFeedback(constant)
    },
    /**
     * Plays a sound effect via AudioManager.
     * Overridable in tests to capture whether a sound was triggered.
     */
    private val soundPlayer: (Int) -> Unit = { effectType ->
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.playSoundEffect(effectType)
    },
) {

    /**
     * Fire feedback for a key-down event on [hostView].
     *
     * [hostView] must be attached to a window for [View.performHapticFeedback] to work.
     * Calls made before the view is attached are silently ignored by the platform.
     */
    fun onKeyDown(hostView: View) {
        if (hapticsEnabled) {
            // FLAG_IGNORE_VIEW_SETTING is NOT used — we respect the view's own haptic flag.
            // FLAG_IGNORE_GLOBAL_SETTING is NOT used — we respect the system haptics switch.
            hapticPerformer(hostView, HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (soundEnabled) {
            // loadSoundEffects() is called lazily by AudioManager; we do not need to manage it.
            // playSoundEffect already gates on the current ringer mode and system volume, so
            // there is nothing extra to check here — it is a no-op in silent/vibrate mode.
            soundPlayer(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }

    /**
     * Returns true if the system-level haptic feedback preference is currently enabled.
     *
     * This mirrors what the platform checks internally inside [View.performHapticFeedback].
     * Exposed so the IME service can show a disabled-state indicator in settings if desired;
     * not used internally (the platform check inside performHapticFeedback is sufficient).
     */
    fun isSystemHapticsEnabled(): Boolean {
        @Suppress("DEPRECATION")  // HAPTIC_FEEDBACK_ENABLED has no non-deprecated replacement
        return Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            /* default = */ 1,
        ) != 0
    }
}
