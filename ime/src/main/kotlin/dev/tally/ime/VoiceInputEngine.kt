package dev.tally.ime

/**
 * Contract for the on-device voice input back-end (T5.2).
 *
 * Implementations must satisfy three invariants:
 *  1. Entirely on-device — no network requests, no INTERNET permission required.
 *  2. Opt-in — the engine is only activated by an explicit user gesture (voice key press).
 *  3. Isolated — the IME uses this interface; the concrete implementation lives in the
 *     `ime` module alongside Android framework calls so `keyboard-engine` stays network-less.
 *
 * The canonical implementation is [OsVoiceInputEngine], which delegates to the OS on-device
 * speech recognizer where available. When the OS recognizer is absent the caller should
 * present a user-facing message; a bundled Vosk model can be substituted by replacing
 * this engine without touching any other code.
 */
internal interface VoiceInputEngine {

    /**
     * Whether the recognizer is currently recording audio.
     *
     * UI may reflect this to show a recording indicator.
     */
    val isListening: Boolean

    /**
     * Begin a recognition session.
     *
     * If a session is already active it is stopped first. The [onResult] callback receives
     * each final transcript on the caller's (main) thread. The [onError] callback fires if
     * recognition fails; the [VoiceError] value indicates whether the failure is recoverable.
     *
     * The caller must hold RECORD_AUDIO permission before invoking this method; the engine
     * does not request the permission itself — that is the responsibility of the IME service.
     */
    fun startListening(onResult: (String) -> Unit, onError: (VoiceError) -> Unit)

    /**
     * Stop a recognition session and free audio resources.
     *
     * Safe to call even when not listening. The engine guarantees that [onResult] and
     * [onError] are not called after this returns.
     */
    fun stopListening()

    /**
     * Release all resources held by the engine.
     *
     * Called when the IME service is destroyed. After this call the engine must not be used.
     */
    fun destroy()
}

/**
 * Recoverable vs fatal failures from [VoiceInputEngine.startListening].
 */
internal enum class VoiceError {
    /**
     * No speech was detected within the timeout window.
     *
     * The user can simply try again without changing any configuration.
     */
    NO_SPEECH,

    /**
     * The OS speech recognizer is not installed or is unavailable on this device.
     *
     * Attempting to start recognition again on the same device will produce the same error.
     * Consider prompting the user to install an on-device recognizer or to switch to keyboard
     * input; a Vosk-backed implementation would not emit this error.
     */
    RECOGNIZER_UNAVAILABLE,

    /**
     * A transient failure such as an audio hardware conflict.
     *
     * The user may retry after a brief pause; repeated failures indicate a permanent issue.
     */
    AUDIO_ERROR,

    /**
     * An unexpected failure not covered by the more specific cases above.
     */
    UNKNOWN,
}
