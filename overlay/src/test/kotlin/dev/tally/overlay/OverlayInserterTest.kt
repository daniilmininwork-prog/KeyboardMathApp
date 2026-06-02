package dev.tally.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OverlayInserter.splice].
 *
 * splice() is pure-JVM logic; no Robolectric or Android framework required.
 *
 * TO.1 acceptance criteria: the clipboard path is deleted and insertion is
 * clipboard-free. These tests verify the splice helper that underpins
 * [OverlayInserter.insert]'s text reconstruction.
 */
class OverlayInserterTest {

    @Test
    fun `splice inserts at collapsed cursor in middle of text`() {
        assertEquals("hello there world", OverlayInserter.splice("hello world", 5, 5, " there"))
    }

    @Test
    fun `splice replaces selection span with result`() {
        assertEquals("2+2=4", OverlayInserter.splice("2+2=?", 4, 5, "4"))
    }

    @Test
    fun `splice appends when cursor is at end of field`() {
        assertEquals("2+2=4", OverlayInserter.splice("2+2=", 4, 4, "4"))
    }

    @Test
    fun `splice handles empty field`() {
        assertEquals("42", OverlayInserter.splice("", 0, 0, "42"))
    }

    @Test
    fun `splice with cursor at position zero inserts at beginning`() {
        assertEquals("Xhello", OverlayInserter.splice("hello", 0, 0, "X"))
    }

    @Test
    fun `splice replaces entire field content when selection covers all`() {
        assertEquals("4", OverlayInserter.splice("2+2=", 0, 4, "4"))
    }

    @Test
    fun `splice clamps out-of-range selStart beyond text length`() {
        // Should not throw; inserts at end
        val result = OverlayInserter.splice("abc", 99, 99, "X")
        assertEquals("abcX", result)
    }

    @Test
    fun `splice handles selStart greater than selEnd by clamping`() {
        // Reversed selection is invalid input but must not crash.
        val result = OverlayInserter.splice("hello", selStart = 4, selEnd = 2, replacement = "X")
        assertTrue("Result must contain replacement and not throw",
            result.contains("X"))
    }

    @Test
    fun `splice preserves unicode characters in surrounding text`() {
        val text = "100÷4="
        assertEquals("100÷4=25", OverlayInserter.splice(text, text.length, text.length, "25"))
    }

    @Test
    fun `splice with empty replacement deletes the selected range`() {
        assertEquals("helo", OverlayInserter.splice("hello", 3, 4, ""))
    }

    @Test
    fun `splice of zero-length selection with empty replacement is identity`() {
        assertEquals("hello", OverlayInserter.splice("hello", 2, 2, ""))
    }

    @Test
    fun `splice negative selStart treated as zero`() {
        // Samsung fields can report -1 for selStart; service normalises before calling splice,
        // but splice itself should also clamp defensively.
        val result = OverlayInserter.splice("abc", selStart = -1, selEnd = 0, replacement = "X")
        assertEquals("Xabc", result)
    }

    // ── TO.3: fallback ladder (02 §4.8) ───────────────────────────────────────

    @Test
    fun `resolveResult tier 1 when set-text verified and caret restored`() {
        assertEquals(
            OverlayInserter.InsertResult.INSERTED,
            OverlayInserter.resolveResult(setTextOk = true, readBackMatches = true, caretRestored = true),
        )
    }

    @Test
    fun `resolveResult tier 2 when set-text verified but selection restore fails`() {
        assertEquals(
            OverlayInserter.InsertResult.INSERTED_AT_END,
            OverlayInserter.resolveResult(setTextOk = true, readBackMatches = true, caretRestored = false),
        )
    }

    @Test
    fun `resolveResult tier 3 when set-text rejected`() {
        // Editor rejects ACTION_SET_TEXT (WebView / Compose / OEM). caret/read-back irrelevant.
        assertEquals(
            OverlayInserter.InsertResult.FAILED,
            OverlayInserter.resolveResult(setTextOk = false, readBackMatches = false, caretRestored = false),
        )
        assertEquals(
            OverlayInserter.InsertResult.FAILED,
            OverlayInserter.resolveResult(setTextOk = false, readBackMatches = true, caretRestored = true),
        )
    }

    @Test
    fun `resolveResult tier 3 on read-back mismatch even when set-text reported success`() {
        // AC-3: a mismatch after a "successful" SET_TEXT (Samsung duplication) must NOT be
        // reported as inserted — the field is left untouched and the result is FAILED.
        assertEquals(
            OverlayInserter.InsertResult.FAILED,
            OverlayInserter.resolveResult(setTextOk = true, readBackMatches = false, caretRestored = true),
        )
    }

    // ── TO.3: selection normalization (Samsung -1 cursor) ─────────────────────

    @Test
    fun `normalizeSelection passes through a valid collapsed caret`() {
        assertEquals(3 to 3, OverlayInserter.normalizeSelection(3, 3, 10))
    }

    @Test
    fun `normalizeSelection appends at end when end is reported as -1 (hide reshow repro)`() {
        // After hide/reshow on Samsung, textSelectionEnd is frequently -1. Insertion must
        // append at the end of the field rather than be dropped.
        assertEquals(5 to 5, OverlayInserter.normalizeSelection(-1, -1, 5))
    }

    @Test
    fun `normalizeSelection clamps an out-of-range end to field length`() {
        assertEquals(4 to 4, OverlayInserter.normalizeSelection(99, 99, 4))
    }

    @Test
    fun `normalizeSelection preserves a real selection span`() {
        assertEquals(2 to 5, OverlayInserter.normalizeSelection(2, 5, 8))
    }

    // ── TO.3: Samsung pre-filled field repro (logic level) ────────────────────

    @Test
    fun `insert into pre-filled field replaces the matched expression span, no duplication`() {
        // Pre-filled field "total: 2+2=" with the "2+2=" span selected for replacement.
        // The full-field reconstruction must not duplicate the prefix (first-letter-repeat /
        // text-duplication is the Samsung failure signal in 05 §7.3).
        val field = "total: 2+2="
        val matchStart = "total: ".length
        val spliced = OverlayInserter.splice(field, matchStart, field.length, "4")
        assertEquals("total: 4", spliced)
    }

    @Test
    fun `append into pre-filled field with -1 cursor keeps existing content intact`() {
        // Pre-filled "2+2=" with no published caret (-1). Normalize → append; splice must
        // keep the existing text and add the result without dropping or duplicating it.
        val field = "2+2="
        val (start, end) = OverlayInserter.normalizeSelection(-1, -1, field.length)
        assertEquals("2+2=4", OverlayInserter.splice(field, start, end, "4"))
    }
}
