package dev.tally.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Verifies that [Suggestion.span] correctly identifies the expression range within the full
 * text-before-cursor string, and that the deletion math in commitResult (replaceExpression=true)
 * does not corrupt surrounding text.
 *
 * These tests exercise issue #1 / #19 in the M0 review: the replace-mode deletion length must
 * span from span.first up to the cursor (i.e. textBeforeCursor.length - span.first characters),
 * NOT just the expression token length (span.last - span.first + 1), which omits the trailing
 * '=' and any whitespace between the expression and the cursor.
 *
 * The actual InputConnection deletion is tested here as a pure arithmetic assertion so it can
 * run without Android or Robolectric. The expected behaviour:
 *
 *   text = "hello 2+2="   → span = 6..8   (the "2+2" substring)
 *   cursor is at index 10 (= textBeforeCursor.length)
 *   correct deleteCount = textBeforeCursor.length - span.first = 10 - 6 = 4  (deletes "2+2=")
 *   wrong  deleteCount  = span.last - span.first + 1           = 8 - 6 + 1 = 3  (leaves "2+=")
 */
class CommitReplaceTest {

    private val locale = Locale.US

    // ── span correctness ──────────────────────────────────────────────────────

    @Test
    fun `span points to the expression substring in a plain expression`() {
        val input = "2+2="
        val suggestion = MathEngine.evaluate(input, locale)
        assertNotNull(suggestion, "should produce a suggestion for '$input'")
        val span = suggestion!!.span

        // The expression "2+2" occupies indices 0..2 in "2+2=" (= textBeforeCursor)
        assertEquals(0, span.first, "span.first should be 0 for '$input'")
        assertEquals(2, span.last,  "span.last  should be 2 for '$input'")
        assertEquals("2+2", input.substring(span.first, span.last + 1))
    }

    @Test
    fun `span points to the expression substring when preceded by prose`() {
        val input = "hello 2+2="
        val suggestion = MathEngine.evaluate(input, locale)
        assertNotNull(suggestion, "should produce a suggestion for '$input'")
        val span = suggestion!!.span

        // The expression "2+2" occupies indices 6..8 in "hello 2+2="
        assertEquals(6, span.first, "span.first should be 6 for '$input'")
        assertEquals(8, span.last,  "span.last  should be 8 for '$input'")
        assertEquals("2+2", input.substring(span.first, span.last + 1))
    }

    @Test
    fun `span points to the expression when there is trailing whitespace before the equals`() {
        // Detector trims trailing whitespace before the '='; the expression still has correct span.
        val input = "2+2 ="
        val suggestion = MathEngine.evaluate(input, locale)
        assertNotNull(suggestion, "should produce a suggestion for '$input'")
        val span = suggestion!!.span

        // "2+2" occupies 0..2; the space and '=' are NOT in the span
        assertEquals(0, span.first, "span.first should be 0 for '$input'")
        assertEquals(2, span.last,  "span.last  should be 2 for '$input'")
    }

    // ── correct deletion math for replace-expression mode ────────────────────

    /**
     * Asserts that the correct replace-mode deleteCount equals
     * `textBeforeCursor.length - span.first` (includes the trailing '=' and whitespace),
     * NOT `span.last - span.first + 1` (which would only delete the expression token).
     */
    @Test
    fun `replace deletion math includes trailing equals for plain expression`() {
        val textBeforeCursor = "2+2="
        val suggestion = MathEngine.evaluate(textBeforeCursor, locale)!!

        // span covers "2+2" (indices 0..2); the trailing '=' is at index 3 and is NOT in span.
        val correctDeleteCount = textBeforeCursor.length - suggestion.span.first
        val wrongDeleteCount   = suggestion.span.last - suggestion.span.first + 1

        // Apply simulated deletion to verify correctness.
        val bufferAfterCorrectDelete = textBeforeCursor.dropLast(correctDeleteCount)
        val bufferAfterWrongDelete   = textBeforeCursor.dropLast(wrongDeleteCount)

        // Correct deletion removes "2+2=" (4 chars) leaving an empty buffer.
        assertEquals("", bufferAfterCorrectDelete,
            "correct delete should remove the entire 'expr=' from the buffer")

        // Wrong deletion removes only the expression token "2+2" (span.last-span.first+1 = 3 chars),
        // leaving the first character of the buffer — demonstrating that the prefix is corrupted.
        // For "2+2=" with span 0..2: dropLast(3) leaves "2" (the leading '2' from the expression).
        val wrongResult = bufferAfterWrongDelete
        assertTrue(
            wrongResult.isNotEmpty(),
            "wrong delete should leave part of the expression in the buffer; got '$wrongResult'"
        )
    }

    @Test
    fun `replace deletion math does not corrupt leading prose`() {
        val textBeforeCursor = "hello 2+2="
        val suggestion = MathEngine.evaluate(textBeforeCursor, locale)!!

        val correctDeleteCount = textBeforeCursor.length - suggestion.span.first

        // After correct deletion the buffer should contain only the prose prefix.
        val bufferAfterDelete = textBeforeCursor.dropLast(correctDeleteCount)
        assertEquals("hello ", bufferAfterDelete,
            "correct delete should leave only the prose prefix intact")

        // Then 'commitText("4", 1)' would append to give "hello 4". Verify the result string.
        assertEquals("hello 4", bufferAfterDelete + suggestion.display)
    }

    @Test
    fun `replace deletion math includes trailing whitespace before equals`() {
        val textBeforeCursor = "2+2 ="  // space between expression and '='
        val suggestion = MathEngine.evaluate(textBeforeCursor, locale)!!

        val correctDeleteCount = textBeforeCursor.length - suggestion.span.first
        val bufferAfterDelete = textBeforeCursor.dropLast(correctDeleteCount)

        // Correct deletion removes "2+2 =" (5 chars) leaving an empty buffer.
        assertEquals("", bufferAfterDelete,
            "correct delete must consume the trailing whitespace and '=' as well")
    }

    // ── off-by-one at the start of the buffer ────────────────────────────────

    @Test
    fun `deleteCount is non-negative for expression at the very start of the buffer`() {
        val textBeforeCursor = "1+1="
        val suggestion = MathEngine.evaluate(textBeforeCursor, locale)!!

        val deleteCount = textBeforeCursor.length - suggestion.span.first
        assertTrue(deleteCount > 0,
            "deleteCount must be positive, got $deleteCount")
        assertTrue(deleteCount <= textBeforeCursor.length,
            "deleteCount must not exceed buffer length")
    }
}
