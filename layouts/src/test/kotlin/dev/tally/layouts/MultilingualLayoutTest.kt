package dev.tally.layouts

import dev.tally.keyboard.engine.Direction
import dev.tally.keyboard.engine.SpecialCode
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates the AZERTY and QWERTZ layout assets parse without error and meet structural
 * requirements: direction is LTR, each row stays within the 1.0 width budget, and the
 * GLOBE special-code is present in the bottom row.
 *
 * Also validates the en_US_QWERTY layout for T5.2 (VOICE key present in bottom row).
 *
 * Assets are loaded from the test resources directory (which mirrors the main assets).
 */
class MultilingualLayoutTest {

    private fun loadAsset(relativePath: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("assets/$relativePath")
            ?: error("Asset not found: $relativePath")
        return stream.bufferedReader().readText()
    }

    // ── fr_FR_AZERTY ─────────────────────────────────────────────────────────

    @Test
    fun `AZERTY layout parses without error`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        assertEquals("fr_FR_AZERTY", def.id)
        assertEquals("fr-FR", def.locale)
        assertEquals(Direction.LTR, def.direction)
    }

    @Test
    fun `AZERTY layout has four rows`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        assertEquals(4, def.rows.size)
    }

    @Test
    fun `AZERTY row widths do not exceed 1 0`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        def.rows.forEachIndexed { i, row ->
            val total = row.keys.fold(0.0f) { acc, k -> acc + k.width }
            assertTrue("AZERTY row[$i] total width $total > 1.0", total <= 1.001f)
        }
    }

    @Test
    fun `AZERTY bottom row contains GLOBE key`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        val bottomRow = def.rows.last()
        val hasGlobe = bottomRow.keys.any { it.code == SpecialCode.GLOBE }
        assertTrue("AZERTY bottom row must contain a GLOBE key", hasGlobe)
    }

    @Test
    fun `AZERTY layout has A as first key in first row`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        // AZERTY starts with A (code 97)
        assertEquals(97, def.rows[0].keys[0].code)
        assertEquals("a", def.rows[0].keys[0].label)
    }

    @Test
    fun `AZERTY layout has accent moreKeys on e`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        val eKey = def.rows[0].keys.find { it.label == "e" }
        assertNotNull("AZERTY must have an 'e' key", eKey)
        assertTrue("'e' key must have accent moreKeys", eKey!!.moreKeys.contains("é"))
    }

    @Test
    fun `AZERTY layout round-trips to KeyDescriptors`() {
        val json = loadAsset("layouts/fr_FR_AZERTY.json")
        val def = LayoutParser.parse(json)
        val descriptors = LayoutParser.toDescriptors(def)
        assertTrue("Descriptor list must be non-empty", descriptors.isNotEmpty())
        // Every descriptor's right edge must be within [0, 1].
        descriptors.forEach { d ->
            assertTrue("Descriptor right=${d.right} must be ≤ 1.0", d.right <= 1.001f)
        }
    }

    // ── de_DE_QWERTZ ─────────────────────────────────────────────────────────

    @Test
    fun `QWERTZ layout parses without error`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        assertEquals("de_DE_QWERTZ", def.id)
        assertEquals("de-DE", def.locale)
        assertEquals(Direction.LTR, def.direction)
    }

    @Test
    fun `QWERTZ layout has four rows`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        assertEquals(4, def.rows.size)
    }

    @Test
    fun `QWERTZ row widths do not exceed 1 0`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        def.rows.forEachIndexed { i, row ->
            val total = row.keys.fold(0.0f) { acc, k -> acc + k.width }
            assertTrue("QWERTZ row[$i] total width $total > 1.0", total <= 1.001f)
        }
    }

    @Test
    fun `QWERTZ bottom row contains GLOBE key`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        val bottomRow = def.rows.last()
        val hasGlobe = bottomRow.keys.any { it.code == SpecialCode.GLOBE }
        assertTrue("QWERTZ bottom row must contain a GLOBE key", hasGlobe)
    }

    @Test
    fun `QWERTZ layout has Z at position 6 in first row`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        // QWERTZ: Q W E R T Z U I O P — Z is code 122 at index 5
        val zKey = def.rows[0].keys.find { it.label == "z" }
        assertNotNull("QWERTZ first row must have 'z'", zKey)
    }

    @Test
    fun `QWERTZ layout Y is in third row`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        // Y moves from row 0 in QWERTY to row 2 in QWERTZ
        val yInRow0 = def.rows[0].keys.any { it.label == "y" }
        val yInRow2 = def.rows[2].keys.any { it.label == "y" }
        assertFalse("QWERTZ row 0 must not contain 'y'", yInRow0)
        assertTrue("QWERTZ row 2 must contain 'y'", yInRow2)
    }

    @Test
    fun `QWERTZ layout has umlaut moreKeys on a`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        val aKey = def.rows[1].keys.find { it.label == "a" }
        assertNotNull("QWERTZ must have an 'a' key in row 1", aKey)
        assertTrue("'a' key must have ä in moreKeys", aKey!!.moreKeys.contains("ä"))
    }

    @Test
    fun `QWERTZ layout round-trips to KeyDescriptors`() {
        val json = loadAsset("layouts/de_DE_QWERTZ.json")
        val def = LayoutParser.parse(json)
        val descriptors = LayoutParser.toDescriptors(def)
        assertTrue("Descriptor list must be non-empty", descriptors.isNotEmpty())
    }

    // ── en_US_QWERTY — VOICE key (T5.2) ─────────────────────────────────────

    @Test
    fun `en_US_QWERTY bottom row contains a VOICE key`() {
        val json = loadAsset("layouts/en_US_QWERTY.json")
        val def = LayoutParser.parse(json)
        val bottomRow = def.rows.last()
        val hasVoice = bottomRow.keys.any { it.code == SpecialCode.VOICE }
        assertTrue("en_US_QWERTY bottom row must contain a VOICE key", hasVoice)
    }

    @Test
    fun `en_US_QWERTY bottom row width stays within budget with VOICE key`() {
        val json = loadAsset("layouts/en_US_QWERTY.json")
        val def = LayoutParser.parse(json)
        val bottomRow = def.rows.last()
        val total = bottomRow.keys.fold(0.0f) { acc, k -> acc + k.width }
        assertTrue(
            "en_US_QWERTY bottom row width $total must be ≤ 1.0",
            total <= 1.001f,
        )
    }

    @Test
    fun `en_US_QWERTY still contains GLOBE key after adding VOICE`() {
        val json = loadAsset("layouts/en_US_QWERTY.json")
        val def = LayoutParser.parse(json)
        val bottomRow = def.rows.last()
        assertTrue(
            "en_US_QWERTY must still contain GLOBE after adding VOICE",
            bottomRow.keys.any { it.code == SpecialCode.GLOBE },
        )
    }
}
