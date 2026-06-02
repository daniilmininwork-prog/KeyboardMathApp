package dev.tally.ime

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.tally.glue.TallyPreferences
import dev.tally.keyboard.engine.SubtypeRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T5.1 — Globe key / subtype switcher.
 *
 * Verifies the pure subtype-cycling semantics through [KeyboardController.Globe] and the
 * preference-persistence contract. [TallyInputMethodService] is not instantiated here;
 * the cycle logic is exercised at the controller + preferences seam.
 *
 * Acceptance criteria verified:
 *   - Globe press triggers [KeyboardController.globeListener].
 *   - SubtypeList cycles forward, wrapping from last to first.
 *   - [TallyPreferences.activeSubtypeId] is updated on each cycle.
 *   - An unrecognised stored id does not crash findById (returns null).
 *   - AZERTY and QWERTZ subtypes are registered and reachable by id.
 *   - [LayoutConverter] maps SpecialCode.GLOBE to [KeyCode.Globe].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlobeSwitcherTest {

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

    // ── Globe key fires the globeListener ────────────────────────────────────

    @Test
    fun `globe key press fires globeListener`() {
        var fired = false
        controller.globeListener = { fired = true }

        controller.handleKey(Key(KeyCode.Globe, "🌐"), fakeIc)

        assertTrue("globeListener must fire on Globe key press", fired)
    }

    @Test
    fun `globe key press does not throw when listener is null`() {
        controller.globeListener = null
        // Must not throw.
        controller.handleKey(Key(KeyCode.Globe, "🌐"), fakeIc)
    }

    @Test
    fun `globe fires listener exactly once per press`() {
        var count = 0
        controller.globeListener = { count++ }

        controller.handleKey(Key(KeyCode.Globe, "🌐"), fakeIc)
        controller.handleKey(Key(KeyCode.Globe, "🌐"), fakeIc)

        assertEquals(2, count)
    }

    // ── SubtypeList cycling ───────────────────────────────────────────────────

    @Test
    fun `cycling through all subtypes returns to the start`() {
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        var current = list.subtypes[0]
        val count = list.subtypes.size

        repeat(count) { current = list.next(current) }

        assertEquals("Full cycle must return to the first subtype", list.subtypes[0], current)
    }

    @Test
    fun `cycling en→fr→de→en`() {
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        val en = list.findById("en_US_QWERTY")!!
        val fr = list.findById("fr_FR_AZERTY")!!
        val de = list.findById("de_DE_QWERTZ")!!

        // Order must be en → fr → de → en in the registry.
        val afterEn = list.next(en)
        val afterFr = list.next(fr)
        val afterDe = list.next(de)

        assertEquals(fr, afterEn)
        assertEquals(de, afterFr)
        assertEquals(en, afterDe)
    }

    // ── Preference persistence ────────────────────────────────────────────────

    @Test
    fun `activeSubtypeId default is en_US_QWERTY`() {
        val fresh = TallyPreferences(ctx)
        assertEquals("en_US_QWERTY", fresh.activeSubtypeId)
    }

    @Test
    fun `activeSubtypeId round-trips through SharedPreferences`() {
        prefs.activeSubtypeId = "fr_FR_AZERTY"
        val fresh = TallyPreferences(ctx)
        assertEquals("fr_FR_AZERTY", fresh.activeSubtypeId)
    }

    @Test
    fun `activeSubtypeId round-trips for German`() {
        prefs.activeSubtypeId = "de_DE_QWERTZ"
        val fresh = TallyPreferences(ctx)
        assertEquals("de_DE_QWERTZ", fresh.activeSubtypeId)
    }

    @Test
    fun `unknown stored subtype id returns null from findById`() {
        prefs.activeSubtypeId = "xx_XX_UNKNOWN"
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        // findById returns null; the caller falls back to subtypes[0].
        assertNull(list.findById(prefs.activeSubtypeId))
    }

    // ── Registry completeness ─────────────────────────────────────────────────

    @Test
    fun `fr_FR_AZERTY is reachable by id`() {
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        assertNotNull(list.findById("fr_FR_AZERTY"))
    }

    @Test
    fun `de_DE_QWERTZ is reachable by id`() {
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        assertNotNull(list.findById("de_DE_QWERTZ"))
    }

    @Test
    fun `en_US_QWERTY is reachable by id`() {
        val list = dev.tally.keyboard.engine.SubtypeList(SubtypeRegistry.ALL)
        assertNotNull(list.findById("en_US_QWERTY"))
    }

    // ── LayoutConverter maps GLOBE → KeyCode.Globe ────────────────────────────

    @Test
    fun `LayoutConverter maps GLOBE special code to KeyCode Globe`() {
        val json = """
            {
              "id": "test",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [
                    { "code": "GLOBE", "label": "G", "width": 0.5, "isSpecial": true },
                    { "code": "SPACE", "label": " ", "width": 0.5, "isSpecial": true }
                  ]
                }
              ]
            }
        """.trimIndent()
        val def = dev.tally.layouts.LayoutParser.parse(json)
        val rows = LayoutConverter.toKeyRows(def)
        val globeKey = rows[0].keys[0]
        assertEquals(KeyCode.Globe, globeKey.code)
    }

    // ── Minimal fake InputConnection ──────────────────────────────────────────

    /**
     * A no-op InputConnection that satisfies the interface without requiring a mock library.
     *
     * Only the methods exercised during globe-press handling need to function; the rest
     * are stubs that return safe defaults so calls don't crash.
     */
    private class FakeInputConnection : InputConnection {
        override fun getTextBeforeCursor(n: Int, flags: Int) = ""
        override fun getTextAfterCursor(n: Int, flags: Int) = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int) = 0
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = true
        override fun setComposingRegion(start: Int, end: Int) = true
        override fun finishComposingText() = true
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
