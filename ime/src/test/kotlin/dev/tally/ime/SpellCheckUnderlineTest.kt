package dev.tally.ime

import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for the Stage 5 spell-check red underline.
 *
 * These exercise the composing-region decorator seam end-to-end through [ComposingTextManager]:
 * a misspelled word must be styled with a [SpellUnderline.RedUnderlineSpan]; a known word must not.
 * The [CapturingInputConnection] keeps the raw [CharSequence] passed to setComposingText so the
 * span can be inspected (the shared FakeInputConnection only records call strings).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpellCheckUnderlineTest {

    /** An IC that retains the actual composing CharSequence (spans intact) for inspection. */
    private class CapturingInputConnection : android.view.inputmethod.BaseInputConnection(
        android.widget.TextView(ApplicationProvider.getApplicationContext()),
        false,
    ) {
        var lastComposing: CharSequence? = null
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun finishComposingText(): Boolean { lastComposing = null; return true }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            lastComposing = text
            return true
        }
    }

    private fun manager(
        ic: InputConnection,
        enabled: Boolean,
        misspelled: (String) -> Boolean,
    ): ComposingTextManager {
        val mirror = InputConnectionMirror()
        return ComposingTextManager(mirror).apply {
            composingDecorator = { word ->
                if (!enabled) word
                else SpellUnderline.decorate(word, misspelled(word.toString()))
            }
        }
    }

    private fun typeWord(mgr: ComposingTextManager, ic: InputConnection, word: String) {
        word.forEach { mgr.appendToComposing(it, ic) }
    }

    @Test
    fun unknownWord_getsRedUnderlineSpan() {
        val ic = CapturingInputConnection()
        // Everything except dictionary words is "misspelled"; treat "helo" as unknown.
        val mgr = manager(ic, enabled = true, misspelled = { it == "helo" })

        typeWord(mgr, ic, "helo")

        assertTrue(
            "Composing text for an unknown word must carry a red underline span",
            SpellUnderline.isFlagged(ic.lastComposing ?: ""),
        )
    }

    @Test
    fun knownWord_hasNoUnderlineSpan() {
        val ic = CapturingInputConnection()
        // Nothing is misspelled → no flagging.
        val mgr = manager(ic, enabled = true, misspelled = { false })

        typeWord(mgr, ic, "hello")

        assertFalse(
            "A known word must not be flagged",
            SpellUnderline.isFlagged(ic.lastComposing ?: ""),
        )
    }

    @Test
    fun spellCheckDisabled_unknownWordIsNotFlagged() {
        val ic = CapturingInputConnection()
        // Word is unknown, but the feature is off — the underline must be suppressed.
        val mgr = manager(ic, enabled = false, misspelled = { true })

        typeWord(mgr, ic, "helo")

        assertFalse(
            "When spell-check is disabled no underline is applied even for unknown words",
            SpellUnderline.isFlagged(ic.lastComposing ?: ""),
        )
    }

    @Test
    fun backspaceFromUnknownToKnownPrefix_clearsUnderline() {
        val ic = CapturingInputConnection()
        // "helo" unknown; its 3-letter prefix "hel" considered known here, so backspacing one
        // character must drop the red flag (the decorator re-runs on the shortened buffer).
        val mgr = manager(ic, enabled = true, misspelled = { it == "helo" })

        typeWord(mgr, ic, "helo")
        assertTrue(SpellUnderline.isFlagged(ic.lastComposing ?: ""))

        mgr.deleteCodePointsBefore(1, ic) // "helo" -> "hel"

        assertFalse(
            "Shortening an unknown word to a known prefix must clear the underline",
            SpellUnderline.isFlagged(ic.lastComposing ?: ""),
        )
    }

    @Test
    fun decorate_returnsSameInstanceWhenNotMisspelled() {
        // Hot-path optimisation: a correctly-spelled keystroke must not allocate a Spannable.
        val plain = "hello"
        assertTrue(SpellUnderline.decorate(plain, misspelled = false) === plain)
    }
}
