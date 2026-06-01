package dev.tally.keyboard.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LayoutDefinitionTest {

    private fun singleKey(code: Int, label: String, width: Float, isSpecial: Boolean = false) =
        KeyDef(code = code, label = label, width = width, isSpecial = isSpecial)

    @Test
    fun `LayoutDefinition preserves id and locale`() {
        val def = LayoutDefinition(
            id = "en_US_QWERTY",
            locale = "en-US",
            direction = Direction.LTR,
            rows = emptyList(),
        )
        assertEquals("en_US_QWERTY", def.id)
        assertEquals("en-US", def.locale)
    }

    @Test
    fun `Direction enum values exist`() {
        assertEquals(Direction.LTR, Direction.valueOf("LTR"))
        assertEquals(Direction.RTL, Direction.valueOf("RTL"))
    }

    @Test
    fun `Row preserves key order`() {
        val keys = listOf(
            singleKey('q'.code, "q", 0.1f),
            singleKey('w'.code, "w", 0.1f),
        )
        val row = Row(keys)
        assertEquals(keys, row.keys)
    }

    @Test
    fun `KeyDef defaults moreKeys to empty list`() {
        val key = singleKey('a'.code, "a", 0.1f)
        assertTrue(key.moreKeys.isEmpty())
        assertFalse(key.isSpecial)
    }

    @Test
    fun `KeyDef stores moreKeys`() {
        val key = KeyDef(code = 'a'.code, label = "a", moreKeys = listOf("á", "à", "â"), width = 0.1f)
        assertEquals(listOf("á", "à", "â"), key.moreKeys)
    }

    @Test
    fun `SpecialCode sentinels are negative`() {
        assertTrue(SpecialCode.SHIFT < 0)
        assertTrue(SpecialCode.DELETE < 0)
        assertTrue(SpecialCode.SYMBOLS < 0)
        assertTrue(SpecialCode.ENTER < 0)
        assertTrue(SpecialCode.SPACE < 0)
        assertTrue(SpecialCode.GLOBE < 0)
    }

    @Test
    fun `SpecialCode values are distinct`() {
        val codes = listOf(
            SpecialCode.SHIFT,
            SpecialCode.DELETE,
            SpecialCode.SYMBOLS,
            SpecialCode.ENTER,
            SpecialCode.SPACE,
            SpecialCode.GLOBE,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `KeyDescriptor computes width from left and right`() {
        val key = singleKey('q'.code, "q", 0.1f)
        val desc = KeyDescriptor(keyDef = key, rowIndex = 0, keyIndex = 0, left = 0.0f, right = 0.1f)
        assertEquals(0.1f, desc.width, 1e-6f)
    }

    @Test
    fun `data class equality and copy work for KeyDef`() {
        val original = KeyDef(code = 'z'.code, label = "z", width = 0.15f)
        val copy = original.copy(width = 0.2f)
        assertNotEquals(original, copy)
        assertEquals('z'.code, copy.code)
    }
}
