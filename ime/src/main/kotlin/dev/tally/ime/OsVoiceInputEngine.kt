package dev.tally.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device voice recognition backed by the Android OS recognizer.
 *
 * Strategy (T5.2 / 04 §2.5):
 *  - Prefer the OS on-device speech recognizer (available on most Google-services devices
 *    as "on-device voice typing" or via a side-loaded recognizer APK).
 *  - [RecognizerIntent.EXTRA_PREFER_OFFLINE] instructs the recognizer to operate without a
 *    network round-trip. If the device has no suitable offline model the recognizer may still
 *    fall back to online recognition — callers should be aware that the quality guarantee is
 *    "best-effort offline."
 *  - No INTERNET permission is used by this code; whether the underlying OS recognizer makes
 *    a network call is outside our control, but the manifest stays clean.
 *
 * Isolation guarantee: this class is the only site that touches [SpeechRecognizer]. The
 * [VoiceInputEngine] interface hides it from [TallyInputMethodService], keeping the seam
 * mock-able in unit tests.
 *
 * Thread model: [SpeechRecognizer] must be created and used on the main thread. All callbacks
 * from [RecognitionListener] fire on the main thread and are forwarded to callers unchanged.
 *
 * @param context            An application context. Must not be an activity context.
 * @param availabilityCheck  Returns true when a recognizer service is resolvable. Injected
 *                           for testing; defaults to [SpeechRecognizer.isRecognitionAvailable].
 */
internal class OsVoiceInputEngine(
    private val context: Context,
    private val availabilityCheck: () -> Boolean = { SpeechRecognizer.isRecognitionAvailable(context) },
) : VoiceInputEngine {

    private var recognizer: SpeechRecognizer? = null
    private var pendingResult: ((String) -> Unit)? = null
    private var pendingError: ((VoiceError) -> Unit)? = null

    override var isListening: Boolean = false
        private set

    /**
     * Returns true if the OS has a speech recognizer service available.
     *
     * A false return means [startListening] will immediately call [onError] with
     * [VoiceError.RECOGNIZER_UNAVAILABLE]. Callers may gate the voice key on this check.
     */
    fun isAvailable(): Boolean = availabilityCheck()

    override fun startListening(onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
        stopListening()

        if (!isAvailable()) {
            onError(VoiceError.RECOGNIZER_UNAVAILABLE)
            return
        }

        pendingResult = onResult
        pendingError  = onError

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(Listener())
        recognizer  = sr
        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Request offline processing. On devices without an offline model the OS may still
            // fall back to a network call — this is a platform limitation, not a bug here.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Return the single best hypothesis to keep integration simple.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.startListening(intent)
    }

    override fun stopListening() {
        isListening   = false
        pendingResult = null
        pendingError  = null
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    override fun destroy() {
        stopListening()
    }

    // ── RecognitionListener ───────────────────────────────────────────────────

    private inner class Listener : RecognitionListener {

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text    = matches?.firstOrNull()
            if (text != null) {
                pendingResult?.invoke(text)
            } else {
                pendingError?.invoke(VoiceError.NO_SPEECH)
            }
            pendingResult = null
            pendingError  = null
        }

        override fun onError(error: Int) {
            isListening = false
            val mapped = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_SPEECH

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    // Network-related errors surface when the OS falls back to online mode
                    // despite EXTRA_PREFER_OFFLINE. Map to AUDIO_ERROR so the caller can
                    // retry rather than treating this as a permanent configuration problem.
                    VoiceError.AUDIO_ERROR
                }

                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.RECOGNIZER_UNAVAILABLE

                SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO_ERROR

                else -> {
                    Log.w(TAG, "Unmapped SpeechRecognizer error code $error")
                    VoiceError.UNKNOWN
                }
            }
            pendingError?.invoke(mapped)
            pendingResult = null
            pendingError  = null
        }

        // ── Lifecycle hooks not used for commit logic ──────────────────────────

        override fun onReadyForSpeech(params: Bundle?) { /* recording started */ }
        override fun onBeginningOfSpeech() { /* first audio detected */ }
        override fun onRmsChanged(rmsdB: Float) { /* volume meter — not used */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* raw audio — not used */ }
        override fun onEndOfSpeech() { /* recording stopped — result follows */ }
        override fun onPartialResults(partialResults: Bundle?) { /* partial — not committed */ }
        override fun onEvent(eventType: Int, params: Bundle?) { /* future platform events */ }
    }

    private companion object {
        const val TAG = "OsVoiceInputEngine"
    }
}
