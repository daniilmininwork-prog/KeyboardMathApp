package dev.tally.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TextExtractor].
 *
 * These are pure, framework-free tests — no Robolectric needed.
 *
 * Phase 7 acceptance criteria covered:
 *   - Read scope limited to text before cursor, bounded to MAX_TEXT_LENGTH.
 *   - Empty / degenerate inputs are handled safely (never throws).
 */
class TextExtractorTest {

    @Test
    fun `empty text returns empty string`() {
        assertEquals("", TextExtractor.extractBeforeCursor("", 0))
    }

    @Test
    fun `zero cursor returns empty string`() {
        assertEquals("", TextExtractor.extractBeforeCursor("2+2=", 0))
    }

    @Test
    fun `negative cursor returns empty string`() {
        assertEquals("", TextExtractor.extractBeforeCursor("2+2=", -5))
    }

    @Test
    fun `cursor at end returns full text when within maxLength`() {
        val text = "2+2="
        assertEquals(text, TextExtractor.extractBeforeCursor(text, text.length))
    }

    @Test
    fun `cursor in middle returns text up to cursor only`() {
        // Cursor is at index 3 in "2+2=": user sees "2+2" before cursor
        assertEquals("2+2", TextExtractor.extractBeforeCursor("2+2=4", 3))
    }

    @Test
    fun `long text is truncated to maxLength before cursor`() {
        val prefix = "x".repeat(600)
        val text = prefix + "2+2="
        val cursorEnd = text.length
        val result = TextExtractor.extractBeforeCursor(text, cursorEnd, maxLength = 512)
        assertEquals(512, result.length)
        assertEquals(text.substring(text.length - 512), result)
    }

    @Test
    fun `cursor beyond text length is clamped to text length`() {
        val text = "10*5="
        val result = TextExtractor.extractBeforeCursor(text, 9999)
        assertEquals(text, result)
    }

    @Test
    fun `single character text with cursor at end`() {
        assertEquals("5", TextExtractor.extractBeforeCursor("5", 1))
    }

    @Test
    fun `expression with unicode operators is not mangled`() {
        val text = "100÷4="
        assertEquals(text, TextExtractor.extractBeforeCursor(text, text.length))
    }

    @Test
    fun `maxLength boundary exactly matches available text`() {
        val text = "abc"
        assertEquals("abc", TextExtractor.extractBeforeCursor(text, text.length, maxLength = 3))
    }

    @Test
    fun `maxLength smaller than available text truncates from the left`() {
        val text = "abcde"
        // maxLength=3, cursor=5 → should return "cde"
        assertEquals("cde", TextExtractor.extractBeforeCursor(text, 5, maxLength = 3))
    }
}
