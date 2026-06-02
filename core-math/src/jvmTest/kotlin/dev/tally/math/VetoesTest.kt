package dev.tally.math

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Direct unit tests for [Vetoes.isIdentifierAdjacent].
 *
 * The function is exercised indirectly through [FalsePositiveTest] corpus entries such as
 * "abc-1=" and "item3+4=", but the boundary conditions are not asserted at the unit level.
 * A future refactor that off-by-ones the spanStart-1 or spanEnd index arithmetic would
 * silently corrupt the veto and could cause password-adjacent text like "p@ss3+5=" to
 * produce a chip.
 *
 * Issue #22 in the M3 review.
 */
class VetoesTest {

    // ── isIdentifierAdjacent — letter before span ─────────────────────────────

    @Test
    fun `letter immediately before span — returns true`() {
        // "abc12+3=" — 'c' is immediately before the span start
        val original = "abc12+3="
        // span covers "12+3=" at indices 3..8
        assertTrue(
            Vetoes.isIdentifierAdjacent(original, spanStart = 3, spanEnd = 8),
            "Letter before span must trigger identifier-adjacent veto",
        )
    }

    @Test
    fun `letter at position 0 with span starting at 0 — returns false (no char before)`() {
        // Span starts at position 0 — there is no character before it.
        val original = "2+3="
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 0, spanEnd = 4),
            "Span at position 0 has no preceding character; must NOT trigger veto",
        )
    }

    // ── isIdentifierAdjacent — letter after span ──────────────────────────────

    @Test
    fun `letter immediately after span — returns true`() {
        // "2+3=abc" — 'a' is immediately after the span
        val original = "2+3=abc"
        // span covers "2+3=" at indices 0..4
        assertTrue(
            Vetoes.isIdentifierAdjacent(original, spanStart = 0, spanEnd = 4),
            "Letter after span must trigger identifier-adjacent veto",
        )
    }

    @Test
    fun `span at end of string — returns false (no char after)`() {
        val original = "2+3="
        // Span covers the whole string; spanEnd == original.length → no char after.
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 0, spanEnd = original.length),
            "Span at end has no following character; must NOT trigger veto",
        )
    }

    // ── isIdentifierAdjacent — non-letter neighbours ──────────────────────────

    @Test
    fun `space before span — returns false`() {
        // " 2+3=" — space before is not a letter
        val original = " 2+3="
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 1, spanEnd = 5),
            "Space before span is not a letter; must NOT trigger veto",
        )
    }

    @Test
    fun `space after span — returns false`() {
        // "2+3= hello" — space after span
        val original = "2+3= hello"
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 0, spanEnd = 4),
            "Space after span is not a letter; must NOT trigger veto",
        )
    }

    @Test
    fun `digit before and after span — returns false`() {
        // "1[2+3=]4" — digits on both sides are not letters
        val original = "12+3=4"
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 1, spanEnd = 5),
            "Digits on both sides are not letters; must NOT trigger veto",
        )
    }

    // ── isIdentifierAdjacent — both sides have letters ────────────────────────

    @Test
    fun `letter before AND after span — returns true`() {
        // "a2+3=b" — letter on both sides
        val original = "a2+3=b"
        assertTrue(
            Vetoes.isIdentifierAdjacent(original, spanStart = 1, spanEnd = 5),
            "Letters on both sides must trigger identifier-adjacent veto",
        )
    }

    // ── isIdentifierAdjacent — edge: span covers full string ──────────────────

    @Test
    fun `span covers entire string — returns false`() {
        val original = "2+3="
        assertFalse(
            Vetoes.isIdentifierAdjacent(original, spanStart = 0, spanEnd = original.length),
            "Span covering entire string has no neighbours; must NOT trigger veto",
        )
    }
}
