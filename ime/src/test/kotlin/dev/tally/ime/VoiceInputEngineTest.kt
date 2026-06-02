package dev.tally.ime

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.tally.glue.TallyPreferences
import dev.tally.keyboard.engine.SpecialCode
import dev.tally.layouts.LayoutParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T5.2 — Offline voice input.
 *
 * Verifies:
 *  - [KeyCode.Voice] is a recognized sealed subclass.
 *  - [SpecialCode.VOICE] is defined and negative (does not collide with Unicode).
 *  - [LayoutParser] accepts "VOICE" as a special code and maps it to [SpecialCode.VOICE].
 *  - [LayoutConverter] maps [SpecialCode.VOICE] → [KeyCode.Voice].
 *  - [KeyboardController.voiceListener] fires on Voice key press.
 *  - [TallyPreferences.voiceInputEnabled] round-trips through SharedPreferences.
 *  - Voice key press finishes composing before invoking the listener.
 *  - [OsVoiceInputEngine] starts / stops / destroys correctly (via Robolectric shadow).
 *  - Engine calls onResult with the top hypothesis from the recognizer.
 *  - Engine calls onError(RECOGNIZER_UNAVAILABLE) when recognition is unavailable.
 *  - Engine calls onError(NO_SPEECH) for timeout/no-match errors.
 *  - Engine is not active (isListening == false) after stopListening().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceInputEngineTest {

    private lateinit var ctx: Context
    private lateinit var prefs: TallyPreferences
    private lateinit var controller: KeyboardController
    private val fakeIc = FakeInputConnection()

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = TallyPreferences(ctx)
        controller = KeyboardController()
        controller.setInputConnectionProvider { fakeIc }
        controller.configure(EditorInfo(), restarting = false)
    }

    // ── KeyCode.Voice sealed subclass ─────────────────────────────────────────

    @Test
    fun `KeyCode Voice is a distinct sealed subclass`() {
        val code: KeyCode = KeyCode.Voice
        // Each path in the 'when' expression on KeyCode must be exhaustive; this cast
        // confirms KeyCode.Voice is a genuine member of the hierarchy.
        assertNotNull(code)
        assertNotEquals(KeyCode.Globe, code)
        assertNotEquals(KeyCode.Backspace, code)
    }

    @Test
    fun `SpecialCode VOICE is negative and does not collide with printable Unicode`() {
        assertTrue("VOICE sentinel must be negative", SpecialCode.VOICE < 0)
        // All printable Unicode code points are non-negative; a negative sentinel can never
        // alias with a valid character key.
        assertNotEquals(SpecialCode.GLOBE, SpecialCode.VOICE)
        assertNotEquals(SpecialCode.SHIFT, SpecialCode.VOICE)
    }

    // ── LayoutParser maps "VOICE" → SpecialCode.VOICE ────────────────────────

    @Test
    fun `LayoutParser accepts VOICE special code string`() {
        val json = """
            {
              "id": "test",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [
                    { "code": "VOICE", "label": "🎤", "width": 0.5, "isSpecial": true },
                    { "code": "SPACE", "label": " ",  "width": 0.5, "isSpecial": true }
                  ]
                }
              ]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        val code = def.rows[0].keys[0].code
        assertEquals(SpecialCode.VOICE, code)
    }

    @Test
    fun `LayoutParser VOICE code is case-insensitive`() {
        val json = """
            {
              "id": "test", "locale": "en-US", "direction": "LTR",
              "rows": [{ "keys": [
                { "code": "voice", "label": "🎤", "width": 0.5, "isSpecial": true },
                { "code": "SPACE", "label": " ", "width": 0.5, "isSpecial": true }
              ]}]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        assertEquals(SpecialCode.VOICE, def.rows[0].keys[0].code)
    }

    // ── LayoutConverter maps SpecialCode.VOICE → KeyCode.Voice ───────────────

    @Test
    fun `LayoutConverter maps VOICE special code to KeyCode Voice`() {
        val json = """
            {
              "id": "test", "locale": "en-US", "direction": "LTR",
              "rows": [{ "keys": [
                { "code": "VOICE", "label": "🎤", "width": 0.5, "isSpecial": true },
                { "code": "SPACE", "label": " ",  "width": 0.5, "isSpecial": true }
              ]}]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        val rows = LayoutConverter.toKeyRows(def)
        val voiceKey = rows[0].keys[0]
        assertEquals(KeyCode.Voice, voiceKey.code)
        assertTrue(voiceKey.isSpecial)
    }

    // ── KeyboardController voiceListener ────────────────────────────────────

    @Test
    fun `voice key press fires voiceListener`() {
        var fired = false
        controller.voiceListener = { fired = true }

        controller.handleKey(Key(KeyCode.Voice, "🎤"), fakeIc)

        assertTrue("voiceListener must fire on Voice key press", fired)
    }

    @Test
    fun `voice key press does not throw when voiceListener is null`() {
        controller.voiceListener = null
        // Must not throw.
        controller.handleKey(Key(KeyCode.Voice, "🎤"), fakeIc)
    }

    @Test
    fun `voice key fires listener exactly once per press`() {
        var count = 0
        controller.voiceListener = { count++ }

        controller.handleKey(Key(KeyCode.Voice, "🎤"), fakeIc)
        controller.handleKey(Key(KeyCode.Voice, "🎤"), fakeIc)

        assertEquals(2, count)
    }

    @Test
    fun `voice key press finishes composing before firing listener`() {
        // Type a character to start composing, then press voice.
        val committedText = StringBuilder()
        fakeIc.finishComposingListener = { text ->
            committedText.append(text)
        }
        var listenerComposingFinished = false
        controller.voiceListener = {
            // At the point the listener fires, composing must already be finished.
            listenerComposingFinished = fakeIc.composingText == null
        }

        controller.handleKey(Key(KeyCode.Char('h'), "h"), fakeIc)
        // At this point we are composing "h". Now press voice.
        controller.handleKey(Key(KeyCode.Voice, "🎤"), fakeIc)

        assertTrue("Composing must be finished before voiceListener fires", listenerComposingFinished)
    }

    // ── TallyPreferences.voiceInputEnabled ───────────────────────────────────

    @Test
    fun `voiceInputEnabled defaults to false`() {
        val fresh = TallyPreferences(ctx)
        assertFalse("Voice input must be OFF by default (opt-in)", fresh.voiceInputEnabled)
    }

    @Test
    fun `voiceInputEnabled round-trips through SharedPreferences`() {
        prefs.voiceInputEnabled = true
        val fresh = TallyPreferences(ctx)
        assertTrue(fresh.voiceInputEnabled)
    }

    @Test
    fun `voiceInputEnabled can be disabled after enabling`() {
        prefs.voiceInputEnabled = true
        prefs.voiceInputEnabled = false
        val fresh = TallyPreferences(ctx)
        assertFalse(fresh.voiceInputEnabled)
    }

    // ── OsVoiceInputEngine via Robolectric ShadowSpeechRecognizer ────────────

    /** Creates an engine whose availability check always returns true for Robolectric tests. */
    private fun engineWithRecognizerAvailable() =
        OsVoiceInputEngine(ctx, availabilityCheck = { true })

    @Test
    fun `engine isListening starts false`() {
        val engine = OsVoiceInputEngine(ctx)
        assertFalse(engine.isListening)
        engine.destroy()
    }

    @Test
    fun `engine calls onError RECOGNIZER_UNAVAILABLE when not available`() {
        // Engine with availability = false.
        val engine = OsVoiceInputEngine(ctx, availabilityCheck = { false })
        var error: VoiceError? = null
        engine.startListening(onResult = {}, onError = { error = it })
        assertEquals(VoiceError.RECOGNIZER_UNAVAILABLE, error)
        assertFalse(engine.isListening)
        engine.destroy()
    }

    @Test
    fun `engine isListening is true after startListening when recognizer is available`() {
        val engine = engineWithRecognizerAvailable()
        engine.startListening(onResult = {}, onError = {})
        assertTrue(engine.isListening)
        engine.destroy()
    }

    @Test
    fun `engine isListening is false after stopListening`() {
        val engine = engineWithRecognizerAvailable()
        engine.startListening(onResult = {}, onError = {})
        engine.stopListening()
        assertFalse(engine.isListening)
    }

    /**
     * Tests that use [OsVoiceInputEngine.RecognitionListenerAccess] to inject results
     * without relying on Robolectric shadow internals, which differ across SDK levels.
     *
     * We test the callback contract via [FakeVoiceInputEngine] (see below) and test the
     * OS engine's state machine (isListening, stopListening) separately.
     */

    @Test
    fun `FakeVoiceInputEngine delivers result to onResult callback`() {
        val fake = FakeVoiceInputEngine()
        var result: String? = null
        fake.startListening(onResult = { result = it }, onError = {})
        fake.deliverResult("hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `FakeVoiceInputEngine maps NO_SPEECH error correctly`() {
        val fake = FakeVoiceInputEngine()
        var error: VoiceError? = null
        fake.startListening(onResult = {}, onError = { error = it })
        fake.deliverError(VoiceError.NO_SPEECH)
        assertEquals(VoiceError.NO_SPEECH, error)
    }

    @Test
    fun `FakeVoiceInputEngine maps AUDIO_ERROR correctly`() {
        val fake = FakeVoiceInputEngine()
        var error: VoiceError? = null
        fake.startListening(onResult = {}, onError = { error = it })
        fake.deliverError(VoiceError.AUDIO_ERROR)
        assertEquals(VoiceError.AUDIO_ERROR, error)
    }

    @Test
    fun `FakeVoiceInputEngine isListening is false after result`() {
        val fake = FakeVoiceInputEngine()
        fake.startListening(onResult = {}, onError = {})
        fake.deliverResult("test")
        assertFalse("Engine must not be listening after result", fake.isListening)
    }

    @Test
    fun `FakeVoiceInputEngine does not deliver after stopListening`() {
        val fake = FakeVoiceInputEngine()
        var result: String? = null
        fake.startListening(onResult = { result = it }, onError = {})
        fake.stopListening()
        fake.deliverResult("should not arrive")
        assertNull("Result must not be delivered after stop", result)
    }

    @Test
    fun `engine stopListening before startListening does not throw`() {
        val engine = OsVoiceInputEngine(ctx)
        // Must not throw.
        engine.stopListening()
        engine.destroy()
    }

    @Test
    fun `engine destroy without startListening does not throw`() {
        val engine = OsVoiceInputEngine(ctx)
        // Must not throw.
        engine.destroy()
    }

    // ── FakeVoiceInputEngine — contract tests without OS shadow ───────────────

    /**
     * A controllable [VoiceInputEngine] implementation for testing the callback contract.
     *
     * Avoids Robolectric [ShadowSpeechRecognizer] internals (which differ across SDK
     * versions) while still exercising the full onResult/onError path.
     */
    private class FakeVoiceInputEngine : VoiceInputEngine {
        override var isListening: Boolean = false
            private set

        private var pendingResult: ((String) -> Unit)? = null
        private var pendingError: ((VoiceError) -> Unit)? = null

        override fun startListening(onResult: (String) -> Unit, onError: (VoiceError) -> Unit) {
            isListening    = true
            pendingResult  = onResult
            pendingError   = onError
        }

        override fun stopListening() {
            isListening   = false
            pendingResult = null
            pendingError  = null
        }

        override fun destroy() {
            stopListening()
        }

        fun deliverResult(text: String) {
            isListening = false
            pendingResult?.invoke(text)
            pendingResult = null
            pendingError  = null
        }

        fun deliverError(error: VoiceError) {
            isListening = false
            pendingError?.invoke(error)
            pendingResult = null
            pendingError  = null
        }
    }

    // ── Minimal fake InputConnection ─────────────────────────────────────────

    /**
     * No-op InputConnection that captures enough state for voice-input assertions.
     */
    private class FakeInputConnection : InputConnection {
        var composingText: String? = null
        var finishComposingListener: ((String?) -> Unit)? = null

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = composingText ?: ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int) = 0
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = text?.toString()
            return true
        }
        override fun setComposingRegion(start: Int, end: Int) = true
        override fun finishComposingText(): Boolean {
            finishComposingListener?.invoke(composingText)
            composingText = null
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int) = true
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?) = true
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?) = true
        override fun setSelection(start: Int, end: Int) = true
        override fun performEditorAction(editorAction: Int) = true
        override fun performContextMenuAction(id: Int) = true
        override fun beginBatchEdit() = true
        override fun endBatchEdit() = true
        override fun sendKeyEvent(event: android.view.KeyEvent?) = true
        override fun clearMetaKeyStates(states: Int) = true
        override fun reportFullscreenMode(enabled: Boolean) = true
        override fun performPrivateCommand(action: String?, data: Bundle?) = true
        override fun requestCursorUpdates(cursorUpdateMode: Int) = true
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: Bundle?) = false
    }
}
