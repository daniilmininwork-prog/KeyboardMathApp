package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SubtypeModel], [SubtypeList], and [SubtypeRegistry] (pure JVM — no Android).
 */
class SubtypeModelTest {

    // ── SubtypeList cycle semantics ───────────────────────────────────────────

    @Test
    fun `next advances to the following subtype`() {
        val a = SubtypeModel("a", "en-US", "English", Direction.LTR)
        val b = SubtypeModel("b", "fr-FR", "Français", Direction.LTR)
        val c = SubtypeModel("c", "de-DE", "Deutsch", Direction.LTR)
        val list = SubtypeList(listOf(a, b, c))

        assertEquals(b, list.next(a))
        assertEquals(c, list.next(b))
    }

    @Test
    fun `next wraps from last to first`() {
        val a = SubtypeModel("a", "en-US", "English", Direction.LTR)
        val b = SubtypeModel("b", "fr-FR", "Français", Direction.LTR)
        val list = SubtypeList(listOf(a, b))

        assertEquals(a, list.next(b))
    }

    @Test
    fun `next returns first when current is not in the list`() {
        val a = SubtypeModel("a", "en-US", "English", Direction.LTR)
        val b = SubtypeModel("b", "fr-FR", "Français", Direction.LTR)
        val list = SubtypeList(listOf(a, b))
        val unknown = SubtypeModel("x", "es-ES", "Español", Direction.LTR)

        assertEquals(a, list.next(unknown))
    }

    @Test
    fun `single subtype list cycles back to itself`() {
        val only = SubtypeModel("only", "en-US", "English", Direction.LTR)
        val list = SubtypeList(listOf(only))

        assertEquals(only, list.next(only))
    }

    @Test
    fun `empty SubtypeList throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubtypeList(emptyList())
        }
    }

    // ── SubtypeList.findById ──────────────────────────────────────────────────

    @Test
    fun `findById returns the correct subtype`() {
        val a = SubtypeModel("en_US_QWERTY", "en-US", "English", Direction.LTR)
        val b = SubtypeModel("fr_FR_AZERTY", "fr-FR", "Français", Direction.LTR)
        val list = SubtypeList(listOf(a, b))

        assertEquals(a, list.findById("en_US_QWERTY"))
        assertEquals(b, list.findById("fr_FR_AZERTY"))
    }

    @Test
    fun `findById returns null for unknown id`() {
        val a = SubtypeModel("en_US_QWERTY", "en-US", "English", Direction.LTR)
        val list = SubtypeList(listOf(a))

        assertNull(list.findById("nonexistent"))
    }

    // ── SubtypeRegistry ───────────────────────────────────────────────────────

    @Test
    fun `registry contains at least one subtype`() {
        assertTrue(SubtypeRegistry.ALL.isNotEmpty())
    }

    @Test
    fun `registry contains English QWERTY`() {
        val en = SubtypeRegistry.ALL.find { it.id == "en_US_QWERTY" }
        assertNotNull(en)
        assertEquals("en-US", en!!.locale)
        assertEquals(Direction.LTR, en.direction)
    }

    @Test
    fun `registry contains French AZERTY`() {
        val fr = SubtypeRegistry.ALL.find { it.id == "fr_FR_AZERTY" }
        assertNotNull(fr)
        assertEquals("fr-FR", fr!!.locale)
    }

    @Test
    fun `registry contains German QWERTZ`() {
        val de = SubtypeRegistry.ALL.find { it.id == "de_DE_QWERTZ" }
        assertNotNull(de)
        assertEquals("de-DE", de!!.locale)
    }

    @Test
    fun `assetPath uses layouts directory convention`() {
        val subtype = SubtypeModel("en_US_QWERTY", "en-US", "English", Direction.LTR)
        assertEquals("layouts/en_US_QWERTY.json", SubtypeRegistry.assetPath(subtype))
    }

    @Test
    fun `all registry ids are unique`() {
        val ids = SubtypeRegistry.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all registry locales are non-empty`() {
        SubtypeRegistry.ALL.forEach { subtype ->
            assertTrue(
                subtype.locale.isNotEmpty(),
                "Subtype '${subtype.id}' has empty locale",
            )
        }
    }
}
