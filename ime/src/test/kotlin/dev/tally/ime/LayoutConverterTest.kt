package dev.tally.ime

import dev.tally.keyboard.engine.Direction
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.LayoutDefinition
import dev.tally.keyboard.engine.Row
import dev.tally.keyboard.engine.SpecialCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LayoutConverter].
 *
 * Verifies the conversion from [LayoutDefinition] / [KeyDef] to the [KeyRow] / [Key] view
 * model, including special-code mapping and width-unit scaling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayoutConverterTest {

    // ── Width scaling ─────────────────────────────────────────────────────────

    @Test
    fun `fraction 0_1 converts to widthUnits 1_0`() {
        val rows = LayoutConverter.toKeyRows(singleKeyDef(0.1f, 97, "a"))
        assertEquals(1.0f, rows[0].keys[0].widthUnits, 1e-5f)
    }

    @Test
    fun `fraction 0_2 converts to widthUnits 2_0`() {
        val rows = LayoutConverter.toKeyRows(singleKeyDef(0.2f, SpecialCode.SHIFT, "⇧", isSpecial = true))
        assertEquals(2.0f, rows[0].keys[0].widthUnits, 1e-5f)
    }

    @Test
    fun `fraction 0_5 converts to widthUnits 5_0`() {
        val rows = LayoutConverter.toKeyRows(singleKeyDef(0.5f, SpecialCode.SPACE, " ", isSpecial = true))
        assertEquals(5.0f, rows[0].keys[0].widthUnits, 1e-5f)
    }

    // ── Special code mapping ──────────────────────────────────────────────────

    @Test
    fun `SHIFT code maps to KeyCode_Shift`() {
        val key = singleKey(SpecialCode.SHIFT, "⇧", isSpecial = true)
        assertEquals(KeyCode.Shift, key.code)
    }

    @Test
    fun `DELETE code maps to KeyCode_Backspace`() {
        val key = singleKey(SpecialCode.DELETE, "⌫", isSpecial = true)
        assertEquals(KeyCode.Backspace, key.code)
    }

    @Test
    fun `SYMBOLS code maps to KeyCode_SwitchToSymbols`() {
        val key = singleKey(SpecialCode.SYMBOLS, "#+=", isSpecial = true)
        assertEquals(KeyCode.SwitchToSymbols, key.code)
    }

    @Test
    fun `ENTER code maps to KeyCode_Enter`() {
        val key = singleKey(SpecialCode.ENTER, "↵", isSpecial = true)
        assertEquals(KeyCode.Enter, key.code)
    }

    @Test
    fun `SPACE code maps to KeyCode_Space`() {
        val key = singleKey(SpecialCode.SPACE, " ", isSpecial = true)
        assertEquals(KeyCode.Space, key.code)
    }

    @Test
    fun `ALPHA code maps to KeyCode_SwitchToAlpha`() {
        val key = singleKey(SpecialCode.ALPHA, "ABC", isSpecial = true)
        assertEquals(KeyCode.SwitchToAlpha, key.code)
    }

    @Test
    fun `NUMERIC code maps to KeyCode_SwitchToNumeric`() {
        val key = singleKey(SpecialCode.NUMERIC, "123", isSpecial = true)
        assertEquals(KeyCode.SwitchToNumeric, key.code)
    }

    @Test
    fun `NUMBER_ROW_TOGGLE code maps to KeyCode_ToggleNumberRow`() {
        val key = singleKey(SpecialCode.NUMBER_ROW_TOGGLE, "#", isSpecial = true)
        assertEquals(KeyCode.ToggleNumberRow, key.code)
    }

    // ── Character key mapping ─────────────────────────────────────────────────

    @Test
    fun `character code 97 maps to KeyCode_Char_a`() {
        val key = singleKey(97, "a")
        assertEquals(KeyCode.Char('a'), key.code)
    }

    @Test
    fun `character code 49 maps to KeyCode_Char_1`() {
        val key = singleKey(49, "1")
        assertEquals(KeyCode.Char('1'), key.code)
    }

    // ── Label and metadata passthrough ────────────────────────────────────────

    @Test
    fun `label is preserved`() {
        val key = singleKey(97, "hello")
        assertEquals("hello", key.label)
    }

    @Test
    fun `isSpecial false is preserved`() {
        val key = singleKey(97, "a", isSpecial = false)
        assertFalse(key.isSpecial)
    }

    @Test
    fun `isSpecial true is preserved`() {
        val key = singleKey(SpecialCode.ENTER, "↵", isSpecial = true)
        assertTrue(key.isSpecial)
    }

    @Test
    fun `moreKeys list is preserved`() {
        val keyDef = KeyDef(code = 101, label = "e", moreKeys = listOf("é", "è"), width = 0.1f)
        val def = definitionWithRows(listOf(Row(listOf(keyDef))))
        val key = LayoutConverter.toKeyRows(def)[0].keys[0]
        assertEquals(listOf("é", "è"), key.moreKeys)
    }

    // ── Multi-row layout ──────────────────────────────────────────────────────

    @Test
    fun `multiple rows are all converted`() {
        val def = definitionWithRows(listOf(
            Row(listOf(KeyDef(97, "a", width = 1.0f))),
            Row(listOf(KeyDef(98, "b", width = 1.0f))),
        ))
        val rows = LayoutConverter.toKeyRows(def)
        assertEquals(2, rows.size)
        assertEquals(KeyCode.Char('a'), rows[0].keys[0].code)
        assertEquals(KeyCode.Char('b'), rows[1].keys[0].code)
    }

    @Test
    fun `multiple keys in a row are all converted`() {
        val def = definitionWithRows(listOf(
            Row(listOf(
                KeyDef(97, "a", width = 0.5f),
                KeyDef(98, "b", width = 0.5f),
            )),
        ))
        val keys = LayoutConverter.toKeyRows(def)[0].keys
        assertEquals(2, keys.size)
        assertEquals(KeyCode.Char('a'), keys[0].code)
        assertEquals(KeyCode.Char('b'), keys[1].code)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun singleKeyDef(
        width: Float,
        code: Int,
        label: String,
        isSpecial: Boolean = false,
    ) = definitionWithRows(listOf(Row(listOf(KeyDef(code, label, width = width, isSpecial = isSpecial)))))

    private fun singleKey(code: Int, label: String, isSpecial: Boolean = false): Key =
        LayoutConverter.toKeyRows(singleKeyDef(0.1f, code, label, isSpecial))[0].keys[0]

    private fun definitionWithRows(rows: List<Row>) = LayoutDefinition(
        id        = "test",
        locale    = "en-US",
        direction = Direction.LTR,
        rows      = rows,
    )
}
