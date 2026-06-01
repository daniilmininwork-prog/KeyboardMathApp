package dev.tally.layouts

import dev.tally.keyboard.engine.Direction
import dev.tally.keyboard.engine.SpecialCode
import org.junit.Assert.*
import org.junit.Test

class LayoutParserTest {

    // ── Valid layouts ─────────────────────────────────────────────────────────

    @Test
    fun `parses minimal valid layout`() {
        val json = """
            {
              "id": "test_layout",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                {
                  "keys": [
                    { "code": 97, "label": "a", "width": 0.5 },
                    { "code": 98, "label": "b", "width": 0.5 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val def = LayoutParser.parse(json)
        assertEquals("test_layout", def.id)
        assertEquals("en-US", def.locale)
        assertEquals(Direction.LTR, def.direction)
        assertEquals(1, def.rows.size)
        assertEquals(2, def.rows[0].keys.size)
        assertEquals(97, def.rows[0].keys[0].code)
        assertEquals("a", def.rows[0].keys[0].label)
    }

    @Test
    fun `parses RTL direction`() {
        val json = minimalLayout("RTL")
        val def = LayoutParser.parse(json)
        assertEquals(Direction.RTL, def.direction)
    }

    @Test
    fun `parses LTR direction case-insensitively`() {
        val json = minimalLayout("ltr")
        val def = LayoutParser.parse(json)
        assertEquals(Direction.LTR, def.direction)
    }

    @Test
    fun `parses special key codes from strings`() {
        val json = layoutWithKeys(
            """{ "code": "SHIFT",   "label": "⇧", "width": 0.2, "isSpecial": true }""",
            """{ "code": "DELETE",  "label": "⌫", "width": 0.2, "isSpecial": true }""",
            """{ "code": "SYMBOLS", "label": "?", "width": 0.2, "isSpecial": true }""",
            """{ "code": "ENTER",   "label": "↵", "width": 0.2, "isSpecial": true }""",
            """{ "code": "SPACE",   "label": " ", "width": 0.2, "isSpecial": true }""",
        )
        val keys = LayoutParser.parse(json).rows[0].keys
        assertEquals(SpecialCode.SHIFT,   keys[0].code)
        assertEquals(SpecialCode.DELETE,  keys[1].code)
        assertEquals(SpecialCode.SYMBOLS, keys[2].code)
        assertEquals(SpecialCode.ENTER,   keys[3].code)
        assertEquals(SpecialCode.SPACE,   keys[4].code)
    }

    @Test
    fun `parses GLOBE special code`() {
        val json = layoutWithKeys("""{ "code": "GLOBE", "label": "🌐", "width": 1.0, "isSpecial": true }""")
        val key = LayoutParser.parse(json).rows[0].keys[0]
        assertEquals(SpecialCode.GLOBE, key.code)
        assertTrue(key.isSpecial)
    }

    @Test
    fun `parses moreKeys list`() {
        val json = layoutWithKeys(
            """{ "code": 101, "label": "e", "moreKeys": ["é", "è", "ê"], "width": 1.0 }"""
        )
        val key = LayoutParser.parse(json).rows[0].keys[0]
        assertEquals(listOf("é", "è", "ê"), key.moreKeys)
    }

    @Test
    fun `isSpecial defaults to false when absent`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a", "width": 1.0 }""")
        assertFalse(LayoutParser.parse(json).rows[0].keys[0].isSpecial)
    }

    @Test
    fun `row with sum less than 1 is accepted`() {
        val json = layoutWithKeys(
            """{ "code": 97, "label": "a", "width": 0.3 }""",
            """{ "code": 98, "label": "b", "width": 0.3 }""",
        )
        // sum = 0.6 < 1.0, should not throw
        val def = LayoutParser.parse(json)
        assertEquals(2, def.rows[0].keys.size)
    }

    @Test
    fun `row with sum exactly 1 is accepted`() {
        val json = layoutWithKeys(
            """{ "code": 97, "label": "a", "width": 0.5 }""",
            """{ "code": 98, "label": "b", "width": 0.5 }""",
        )
        val def = LayoutParser.parse(json)
        assertEquals(2, def.rows[0].keys.size)
    }

    @Test
    fun `parses multiple rows preserving order`() {
        val json = """
            {
              "id": "multi",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [{ "code": 97, "label": "a", "width": 1.0 }] },
                { "keys": [{ "code": 98, "label": "b", "width": 1.0 }] },
                { "keys": [{ "code": 99, "label": "c", "width": 1.0 }] }
              ]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        assertEquals(3, def.rows.size)
        assertEquals("a", def.rows[0].keys[0].label)
        assertEquals("b", def.rows[1].keys[0].label)
        assertEquals("c", def.rows[2].keys[0].label)
    }

    // ── Width-fraction validation ──────────────────────────────────────────────

    @Test(expected = LayoutParseException::class)
    fun `rejects row whose width sum exceeds 1`() {
        val json = layoutWithKeys(
            """{ "code": 97, "label": "a", "width": 0.6 }""",
            """{ "code": 98, "label": "b", "width": 0.6 }""",
        )
        LayoutParser.parse(json) // sum = 1.2 > 1.0 → exception
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects row with single key wider than 1`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a", "width": 1.1 }""")
        LayoutParser.parse(json)
    }

    @Test
    fun `width overflow message contains row index`() {
        val json = layoutWithKeys(
            """{ "code": 97, "label": "a", "width": 0.7 }""",
            """{ "code": 98, "label": "b", "width": 0.7 }""",
        )
        try {
            LayoutParser.parse(json)
            fail("Expected LayoutParseException")
        } catch (e: LayoutParseException) {
            assertTrue("message should name the row: ${e.message}", e.message!!.contains("row[0]"))
        }
    }

    // ── Malformed JSON ────────────────────────────────────────────────────────

    @Test(expected = LayoutParseException::class)
    fun `rejects empty string`() {
        LayoutParser.parse("")
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects missing id field`() {
        val json = """
            {
              "locale": "en-US",
              "direction": "LTR",
              "rows": []
            }
        """.trimIndent()
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects unknown direction value`() {
        LayoutParser.parse(minimalLayout("DIAGONAL"))
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects key with missing code`() {
        val json = layoutWithKeys("""{ "label": "a", "width": 0.5 }""")
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects key with missing label`() {
        val json = layoutWithKeys("""{ "code": 97, "width": 0.5 }""")
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects key with missing width`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a" }""")
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects key with zero width`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a", "width": 0.0 }""")
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects key with negative width`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a", "width": -0.1 }""")
        LayoutParser.parse(json)
    }

    @Test(expected = LayoutParseException::class)
    fun `rejects unknown special code string`() {
        val json = layoutWithKeys("""{ "code": "MAGIC", "label": "?", "width": 1.0 }""")
        LayoutParser.parse(json)
    }

    // ── Round-trip to keyboard-engine descriptors ─────────────────────────────

    @Test
    fun `toDescriptors produces one descriptor per key`() {
        val json = """
            {
              "id": "t",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [
                    { "code": 97, "label": "a", "width": 0.3 },
                    { "code": 98, "label": "b", "width": 0.3 },
                    { "code": 99, "label": "c", "width": 0.4 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        val descriptors = LayoutParser.toDescriptors(def)
        assertEquals(3, descriptors.size)
    }

    @Test
    fun `toDescriptors assigns correct fractional edges`() {
        val json = """
            {
              "id": "t",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [
                    { "code": 97, "label": "a", "width": 0.25 },
                    { "code": 98, "label": "b", "width": 0.5 },
                    { "code": 99, "label": "c", "width": 0.25 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val def = LayoutParser.parse(json)
        val descs = LayoutParser.toDescriptors(def)

        assertEquals(0.0f,  descs[0].left,  1e-5f)
        assertEquals(0.25f, descs[0].right, 1e-5f)
        assertEquals(0.25f, descs[1].left,  1e-5f)
        assertEquals(0.75f, descs[1].right, 1e-5f)
        assertEquals(0.75f, descs[2].left,  1e-5f)
        assertEquals(1.0f,  descs[2].right, 1e-5f)
    }

    @Test
    fun `toDescriptors preserves row and key indices`() {
        val json = """
            {
              "id": "t",
              "locale": "en-US",
              "direction": "LTR",
              "rows": [
                { "keys": [
                    { "code": 97, "label": "a", "width": 0.5 },
                    { "code": 98, "label": "b", "width": 0.5 }
                  ]
                },
                { "keys": [
                    { "code": 99, "label": "c", "width": 1.0 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val descs = LayoutParser.toDescriptors(LayoutParser.parse(json))
        assertEquals(0, descs[0].rowIndex); assertEquals(0, descs[0].keyIndex)
        assertEquals(0, descs[1].rowIndex); assertEquals(1, descs[1].keyIndex)
        assertEquals(1, descs[2].rowIndex); assertEquals(0, descs[2].keyIndex)
    }

    @Test
    fun `toDescriptors preserves keyDef reference`() {
        val json = layoutWithKeys("""{ "code": 97, "label": "a", "width": 1.0 }""")
        val def = LayoutParser.parse(json)
        val descs = LayoutParser.toDescriptors(def)
        assertSame(def.rows[0].keys[0], descs[0].keyDef)
    }

    // ── Reference layout (en_US_QWERTY) ──────────────────────────────────────

    @Test
    fun `reference QWERTY layout parses without error`() {
        val json = javaClass.getResourceAsStream("/assets/layouts/en_US_QWERTY.json")
            ?.bufferedReader()?.readText()
            ?: error("en_US_QWERTY.json not found in test resources")
        val def = LayoutParser.parse(json)
        assertEquals("en_US_QWERTY", def.id)
        assertEquals(Direction.LTR, def.direction)
        assertEquals(4, def.rows.size)
    }

    @Test
    fun `reference QWERTY layout round-trips to descriptors`() {
        val json = javaClass.getResourceAsStream("/assets/layouts/en_US_QWERTY.json")
            ?.bufferedReader()?.readText()
            ?: error("en_US_QWERTY.json not found in test resources")
        val def = LayoutParser.parse(json)
        val descs = LayoutParser.toDescriptors(def)

        // Each row produces a descriptor per key, left edges start at 0.
        def.rows.forEachIndexed { rowIndex, row ->
            val rowDescs = descs.filter { it.rowIndex == rowIndex }
            assertEquals(row.keys.size, rowDescs.size)
            assertEquals(0.0f, rowDescs.first().left, 1e-4f)
        }
    }

    @Test
    fun `reference QWERTY first row contains 10 letter keys`() {
        val json = javaClass.getResourceAsStream("/assets/layouts/en_US_QWERTY.json")
            ?.bufferedReader()?.readText()
            ?: error("en_US_QWERTY.json not found in test resources")
        val def = LayoutParser.parse(json)
        val row0 = def.rows[0]
        assertEquals(10, row0.keys.size)
        assertEquals("q", row0.keys[0].label)
        assertEquals("p", row0.keys[9].label)
    }

    @Test
    fun `reference QWERTY all row width fractions are valid`() {
        val json = javaClass.getResourceAsStream("/assets/layouts/en_US_QWERTY.json")
            ?.bufferedReader()?.readText()
            ?: error("en_US_QWERTY.json not found in test resources")
        val def = LayoutParser.parse(json)
        def.rows.forEachIndexed { idx, row ->
            val total = row.keys.fold(0.0f) { acc, k -> acc + k.width }
            assertTrue("row[$idx] width sum $total exceeds 1.0", total <= 1.0f + 1e-4f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalLayout(direction: String): String = """
        {
          "id": "x",
          "locale": "en-US",
          "direction": "$direction",
          "rows": [{ "keys": [{ "code": 97, "label": "a", "width": 1.0 }] }]
        }
    """.trimIndent()

    private fun layoutWithKeys(vararg keyJsons: String): String = """
        {
          "id": "x",
          "locale": "en-US",
          "direction": "LTR",
          "rows": [{ "keys": [${keyJsons.joinToString(",")}] }]
        }
    """.trimIndent()
}
