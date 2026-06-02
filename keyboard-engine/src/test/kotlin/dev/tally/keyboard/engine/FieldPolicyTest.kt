package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the [FieldPolicy] value object.
 *
 * The class carries no derivation logic (that lives in [dev.tally.ime.FieldPolicyFactory]),
 * so these tests focus on structural guarantees:
 *   - DEFAULT_PRIVATE is maximally restrictive.
 *   - PERMISSIVE is maximally permissive.
 *   - data-class equality and copy work as expected.
 *   - Every field can be toggled independently.
 */
class FieldPolicyTest {

    @Test
    fun `DEFAULT_PRIVATE disables all optional features`() {
        val p = FieldPolicy.DEFAULT_PRIVATE
        assertFalse(p.learningEnabled,    "learning must be off in default-private")
        assertFalse(p.suggestionsEnabled, "suggestions must be off in default-private")
        assertFalse(p.glideEnabled,       "glide must be off in default-private")
        assertTrue (p.previewMasked,      "preview must be masked in default-private")
        assertFalse(p.persistAllowed,     "persist must be disallowed in default-private")
        assertFalse(p.mathEnabled,        "math must be disabled in default-private")
    }

    @Test
    fun `PERMISSIVE enables all optional features`() {
        val p = FieldPolicy.PERMISSIVE
        assertTrue (p.learningEnabled,    "learning must be on in permissive")
        assertTrue (p.suggestionsEnabled, "suggestions must be on in permissive")
        assertTrue (p.glideEnabled,       "glide must be on in permissive")
        assertFalse(p.previewMasked,      "preview must be unmasked in permissive")
        assertTrue (p.persistAllowed,     "persist must be allowed in permissive")
        assertTrue (p.mathEnabled,        "math must be enabled in permissive")
    }

    @Test
    fun `DEFAULT_PRIVATE and PERMISSIVE differ on every field`() {
        val d = FieldPolicy.DEFAULT_PRIVATE
        val p = FieldPolicy.PERMISSIVE
        assertNotEquals(d.learningEnabled,    p.learningEnabled)
        assertNotEquals(d.suggestionsEnabled, p.suggestionsEnabled)
        assertNotEquals(d.glideEnabled,       p.glideEnabled)
        assertNotEquals(d.previewMasked,      p.previewMasked)
        assertNotEquals(d.persistAllowed,     p.persistAllowed)
        assertNotEquals(d.mathEnabled,        p.mathEnabled)
    }

    @Test
    fun `copy produces value equality on identical fields`() {
        val original = FieldPolicy.PERMISSIVE
        val copy = original.copy()
        assertEquals(original, copy)
    }

    @Test
    fun `copy with one field changed differs from original`() {
        val original = FieldPolicy.PERMISSIVE
        val masked = original.copy(previewMasked = true)
        assertNotEquals(original, masked)
        assertTrue(masked.previewMasked)
        assertTrue(masked.learningEnabled)   // other fields unaffected
    }

    @Test
    fun `data class hashCode is consistent with equals`() {
        val a = FieldPolicy.DEFAULT_PRIVATE
        val b = FieldPolicy.DEFAULT_PRIVATE
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString contains all field names`() {
        val s = FieldPolicy.DEFAULT_PRIVATE.toString()
        assertTrue(s.contains("learningEnabled"))
        assertTrue(s.contains("suggestionsEnabled"))
        assertTrue(s.contains("glideEnabled"))
        assertTrue(s.contains("previewMasked"))
        assertTrue(s.contains("persistAllowed"))
        assertTrue(s.contains("mathEnabled"))
    }
}
