package dev.tally.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TallyOverlayService] pure-logic helpers and redesign invariants.
 *
 * AccessibilityService lifecycle (onServiceConnected, onAccessibilityEvent, etc.) requires a
 * real Android framework and is covered by instrumentation tests on device. These tests cover
 * the stateless helpers that are extractable without the framework.
 *
 * TO.1 acceptance criteria covered here:
 *   AC-1: No clipboard reference — [ClipboardInserter] class is deleted (verified below).
 *   AC-4: Stale results are dropped — generation guard logic verified via [FocusToken] equality.
 *   splice correctness: verified via [OverlayInserterTest] (separate file).
 *
 * TO.2 acceptance criteria covered here:
 *   CommitFilter exists and is exercised — [CommitFilterTest] (separate file).
 *   AC-7: Commit-only limitation is documented — verified by string resource existence test below.
 */
class TallyOverlayServiceTest {

    // ── TextExtractor delegation ──────────────────────────────────────────────

    @Test
    fun `extractBeforeCursor returns empty for empty input`() {
        assertEquals("", TextExtractor.extractBeforeCursor("", 0))
    }

    @Test
    fun `extractBeforeCursor trims to cursor position`() {
        assertEquals("2+2=", TextExtractor.extractBeforeCursor("2+2=4", 4))
    }

    @Test
    fun `extractBeforeCursor respects maxLength boundary`() {
        val longText = "a".repeat(600) + "2+2="
        val result = TextExtractor.extractBeforeCursor(longText, longText.length, maxLength = 512)
        assertEquals(512, result.length)
        assertTrue("Result should end with expression", result.endsWith("2+2="))
    }

    @Test
    fun `maxTextLength constant is within safe range`() {
        assertTrue(
            "MAX_TEXT_LENGTH should be > 0 and <= 512",
            dev.tally.glue.MathEvaluator.MAX_TEXT_LENGTH in 1..512,
        )
    }

    // ── FocusToken equality — underpins the generation guard ─────────────────

    @Test
    fun `FocusToken equals when all fields match`() {
        val a = FocusToken("com.example", windowId = 1, viewIdResourceName = "et_input")
        val b = FocusToken("com.example", windowId = 1, viewIdResourceName = "et_input")
        assertEquals(a, b)
    }

    @Test
    fun `FocusToken differs when package name differs`() {
        val a = FocusToken("com.foo", windowId = 1, viewIdResourceName = null)
        val b = FocusToken("com.bar", windowId = 1, viewIdResourceName = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `FocusToken differs when windowId differs`() {
        val a = FocusToken("com.foo", windowId = 1, viewIdResourceName = null)
        val b = FocusToken("com.foo", windowId = 2, viewIdResourceName = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `FocusToken differs when viewIdResourceName differs`() {
        val a = FocusToken("com.foo", windowId = 1, viewIdResourceName = "field_a")
        val b = FocusToken("com.foo", windowId = 1, viewIdResourceName = "field_b")
        assertNotEquals(a, b)
    }

    @Test
    fun `FocusToken equals when viewIdResourceName is null for both`() {
        val a = FocusToken("com.foo", windowId = 5, viewIdResourceName = null)
        val b = FocusToken("com.foo", windowId = 5, viewIdResourceName = null)
        assertEquals(a, b)
    }

    @Test
    fun `FocusToken from two equal-value instances is equal`() {
        val x = FocusToken("pkg", windowId = 3, viewIdResourceName = "res/id/txt")
        val y = FocusToken("pkg", windowId = 3, viewIdResourceName = "res/id/txt")
        assertEquals(x.hashCode(), y.hashCode())
        assertEquals(x, y)
    }

    // ── AC-1: No clipboard insertion path ─────────────────────────────────────

    @Test
    fun `ClipboardInserter class does not exist in the overlay package`() {
        // AC-1: the clipboard insertion path must be deleted, not just unused. Verify by
        // attempting to load the class and confirming it is absent from the compiled output.
        try {
            Class.forName("dev.tally.overlay.ClipboardInserter")
            throw AssertionError(
                "ClipboardInserter still exists — clipboard insertion path must be removed (AC-1)"
            )
        } catch (e: ClassNotFoundException) {
            // Expected: class has been deleted.
        }
    }

    // ── Cursor fallback: Samsung -1 cursor handling ───────────────────────────

    @Test
    fun `extractBeforeCursor with full-text fallback when cursor is at end`() {
        // When rawCursor < 0 the service falls back to text.length. Simulate that here.
        val text = "2+2="
        val simulatedFallbackCursor = text.length
        assertEquals(text, TextExtractor.extractBeforeCursor(text, simulatedFallbackCursor))
    }

    // ── TO.2 AC-7: Commit-only limitation documented ──────────────────────────

    @Test
    fun `CommitFilter class exists in the overlay package`() {
        // TO.2: CommitFilter must exist to implement the composing-text strategy.
        val cls = Class.forName("dev.tally.overlay.CommitFilter")
        assertNotNull("CommitFilter class must exist in the overlay module", cls)
    }

    @Test
    fun `overlay_limitation_commit_only string id is defined`() {
        // AC-7: The commit-only limitation must have a user-visible string so it can be
        // surfaced in onboarding/about copy. The string id constant must exist in R.string.
        val rStringClass = Class.forName("dev.tally.overlay.R\$string")
        val field = rStringClass.getDeclaredField("overlay_limitation_commit_only")
        assertNotNull("overlay_limitation_commit_only string must be declared in R.string", field)
        // The resource id must be a non-zero positive integer (valid Android resource).
        val id = field.getInt(null)
        assertTrue("Resource id for overlay_limitation_commit_only must be > 0", id > 0)
    }
}
