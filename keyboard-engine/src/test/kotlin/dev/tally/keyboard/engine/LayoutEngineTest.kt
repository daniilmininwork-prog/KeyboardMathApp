package dev.tally.keyboard.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for [DefaultLayoutEngine] covering geometry resolution and hit-testing.
 *
 * All tests use plain JVM data — no Android View or Context is involved. That is the
 * whole point of keeping LayoutEngine in the pure-JVM module.
 */
class LayoutEngineTest {

    private val engine: LayoutEngine = DefaultLayoutEngine

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun key(code: Int, label: String, width: Float, isSpecial: Boolean = false) =
        KeyDef(code = code, label = label, width = width, isSpecial = isSpecial)

    /** A minimal two-row layout whose rows each sum to 1.0. */
    private fun twoRowLayout(): LayoutDefinition {
        val topRow = Row(listOf(
            key('q'.code, "q", 0.25f),
            key('w'.code, "w", 0.25f),
            key('e'.code, "e", 0.25f),
            key('r'.code, "r", 0.25f),
        ))
        val bottomRow = Row(listOf(
            key(SpecialCode.SHIFT,  "⇧", 0.5f, isSpecial = true),
            key(SpecialCode.SPACE,  " ",  0.5f, isSpecial = true),
        ))
        return LayoutDefinition(
            id        = "test_LTR",
            locale    = "en-US",
            direction = Direction.LTR,
            rows      = listOf(topRow, bottomRow),
        )
    }

    // ── layoutFor: basic geometry ─────────────────────────────────────────────

    @Test
    fun `layoutFor produces one ResolvedKey per KeyDef`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)
        // 4 keys in row 0 + 2 keys in row 1
        assertEquals(6, geo.keys.size)
    }

    @Test
    fun `layoutFor divides height uniformly across rows`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)
        assertEquals(100f, geo.rowHeight, 1e-3f)
    }

    @Test
    fun `layoutFor assigns correct pixel widths proportional to fractions`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)

        // Row 0: all keys are 0.25 × 400 = 100 px wide
        val qKey = geo.keys.first { it.keyDef.code == 'q'.code }
        assertEquals(100f, qKey.width, 1e-3f)

        // Row 1: shift is 0.5 × 400 = 200 px wide
        val shiftKey = geo.keys.first { it.keyDef.code == SpecialCode.SHIFT }
        assertEquals(200f, shiftKey.width, 1e-3f)
    }

    @Test
    fun `layoutFor left-edges are cumulative sums within each row`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)

        val row0 = geo.keys.filter { it.id.rowIndex == 0 }.sortedBy { it.id.keyIndex }
        assertEquals(0f,   row0[0].left, 1e-3f)
        assertEquals(100f, row0[1].left, 1e-3f)
        assertEquals(200f, row0[2].left, 1e-3f)
        assertEquals(300f, row0[3].left, 1e-3f)
    }

    @Test
    fun `layoutFor top-bottom edges correspond to row bands`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)

        val row0Keys = geo.keys.filter { it.id.rowIndex == 0 }
        row0Keys.forEach { key ->
            assertEquals(0f,   key.top,    1e-3f)
            assertEquals(100f, key.bottom, 1e-3f)
        }

        val row1Keys = geo.keys.filter { it.id.rowIndex == 1 }
        row1Keys.forEach { key ->
            assertEquals(100f, key.top,    1e-3f)
            assertEquals(200f, key.bottom, 1e-3f)
        }
    }

    @Test
    fun `layoutFor preserves KeyDef in each ResolvedKey`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)
        val eKey = geo.keys.first { it.keyDef.code == 'e'.code }
        assertEquals("e", eKey.keyDef.label)
    }

    @Test
    fun `layoutFor assigns stable KeyId rowIndex and keyIndex`() {
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)
        val wKey = geo.keys.first { it.keyDef.code == 'w'.code }
        assertEquals(KeyId(rowIndex = 0, keyIndex = 1), wKey.id)
    }

    @Test
    fun `layoutFor stores viewport dimensions in KeyGeometry`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 480, height = 240)
        assertEquals(480, geo.viewportWidth)
        assertEquals(240, geo.viewportHeight)
    }

    // ── layoutFor: edge cases ─────────────────────────────────────────────────

    @Test
    fun `layoutFor handles row whose fractions sum to less than 1`() {
        // Row sum = 0.6; remaining 0.4 is trailing padding — this is valid per spec.
        val row = Row(listOf(
            key('a'.code, "a", 0.3f),
            key('b'.code, "b", 0.3f),
        ))
        val def = LayoutDefinition(id = "under", locale = "en-US", direction = Direction.LTR, rows = listOf(row))
        val geo = engine.layoutFor(def, width = 100, height = 50)

        val aKey = geo.keys[0]
        val bKey = geo.keys[1]
        assertEquals(0f,  aKey.left,  1e-3f)
        assertEquals(30f, aKey.right, 1e-3f)
        assertEquals(30f, bKey.left,  1e-3f)
        assertEquals(60f, bKey.right, 1e-3f)
    }

    @Test
    fun `layoutFor throws when width is zero`() {
        assertThrows<IllegalArgumentException> {
            engine.layoutFor(twoRowLayout(), width = 0, height = 200)
        }
    }

    @Test
    fun `layoutFor throws when height is zero`() {
        assertThrows<IllegalArgumentException> {
            engine.layoutFor(twoRowLayout(), width = 400, height = 0)
        }
    }

    @Test
    fun `layoutFor throws when width is negative`() {
        assertThrows<IllegalArgumentException> {
            engine.layoutFor(twoRowLayout(), width = -1, height = 200)
        }
    }

    @Test
    fun `layoutFor throws when definition has no rows`() {
        val empty = LayoutDefinition(id = "empty", locale = "en-US", direction = Direction.LTR, rows = emptyList())
        assertThrows<IllegalArgumentException> {
            engine.layoutFor(empty, width = 400, height = 200)
        }
    }

    @Test
    fun `layoutFor handles a single-row layout`() {
        val row = Row(listOf(
            key('a'.code, "a", 0.5f),
            key('b'.code, "b", 0.5f),
        ))
        val def = LayoutDefinition(id = "single", locale = "en-US", direction = Direction.LTR, rows = listOf(row))
        val geo = engine.layoutFor(def, width = 200, height = 100)

        assertEquals(100f, geo.rowHeight, 1e-3f)
        assertEquals(0f,   geo.keys[0].top,    1e-3f)
        assertEquals(100f, geo.keys[0].bottom, 1e-3f)
    }

    // ── hitTest: exact hits ───────────────────────────────────────────────────

    @Test
    fun `hitTest returns correct KeyId for a point inside a key`() {
        // 4 equal-width keys across 400 px; each key is 100 px wide
        val def = twoRowLayout()
        val geo = engine.layoutFor(def, width = 400, height = 200)

        // Centre of 'w' (keyIndex 1): x = 150, y = 50
        val id = engine.hitTest(geo, x = 150f, y = 50f)
        assertNotNull(id)
        assertEquals(KeyId(rowIndex = 0, keyIndex = 1), id)
        assertEquals('w'.code, geo.keyById(id!!)!!.keyDef.code)
    }

    @Test
    fun `hitTest returns the key at the top-left corner`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        val id = engine.hitTest(geo, x = 0f, y = 0f)
        assertEquals(KeyId(rowIndex = 0, keyIndex = 0), id)
    }

    @Test
    fun `hitTest returns correct row for a point in the second row`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        // Row 1 starts at y = 100; point at (50, 150) should be in the SHIFT key
        val id = engine.hitTest(geo, x = 50f, y = 150f)
        assertNotNull(id)
        assertEquals(1, id!!.rowIndex)
        assertEquals(SpecialCode.SHIFT, geo.keyById(id)!!.keyDef.code)
    }

    @Test
    fun `hitTest returns null for a point outside all keys (below the keyboard)`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        assertNull(engine.hitTest(geo, x = 200f, y = 250f))
    }

    @Test
    fun `hitTest returns null for a point outside all keys (right of the keyboard)`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        assertNull(engine.hitTest(geo, x = 450f, y = 50f))
    }

    @Test
    fun `hitTest returns null for a negative x coordinate`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        assertNull(engine.hitTest(geo, x = -1f, y = 50f))
    }

    @Test
    fun `hitTest returns null for a negative y coordinate`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        assertNull(engine.hitTest(geo, x = 50f, y = -1f))
    }

    // ── hitTest: boundary conditions ──────────────────────────────────────────

    @Test
    fun `hitTest at the exact left edge of a key resolves to that key`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        // 'e' starts at x = 200; exact left edge should land on 'e' (keyIndex 2)
        val id = engine.hitTest(geo, x = 200f, y = 50f)
        assertNotNull(id)
        assertEquals('e'.code, geo.keyById(id!!)!!.keyDef.code)
    }

    @Test
    fun `hitTest at the exact right boundary is exclusive (goes to next key or null)`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        // 'q' spans [0, 100); point at x=100 should land on 'w', not 'q'
        val id = engine.hitTest(geo, x = 100f, y = 50f)
        assertNotNull(id)
        assertEquals('w'.code, geo.keyById(id!!)!!.keyDef.code)
    }

    @Test
    fun `hitTest at the exact row boundary belongs to the lower row`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        // Row 0 is [0, 100); y=100 should land in row 1
        val id = engine.hitTest(geo, x = 50f, y = 100f)
        assertNotNull(id)
        assertEquals(1, id!!.rowIndex)
    }

    // ── RTL layout geometry ───────────────────────────────────────────────────

    @Test
    fun `layoutFor mirrors key order for RTL layouts`() {
        // Three equal-width keys labelled A, B, C; in RTL the leftmost pixel position
        // should be C (the last key in the source list), not A.
        val row = Row(listOf(
            key('A'.code, "A", 1f / 3f),
            key('B'.code, "B", 1f / 3f),
            key('C'.code, "C", 1f / 3f),
        ))
        val def = LayoutDefinition(id = "rtl", locale = "he", direction = Direction.RTL, rows = listOf(row))
        val geo = engine.layoutFor(def, width = 300, height = 50)

        // In RTL the row is reversed for rendering: C lands leftmost (left=0)
        val leftmost = geo.keys.minByOrNull { it.left }!!
        assertEquals('C'.code, leftmost.keyDef.code)
    }

    @Test
    fun `hitTest works correctly on an RTL layout`() {
        val row = Row(listOf(
            key('A'.code, "A", 1f / 3f),
            key('B'.code, "B", 1f / 3f),
            key('C'.code, "C", 1f / 3f),
        ))
        val def = LayoutDefinition(id = "rtl", locale = "he", direction = Direction.RTL, rows = listOf(row))
        val geo = engine.layoutFor(def, width = 300, height = 50)

        // In the RTL layout, tapping at x=50 should hit the leftmost rendered key = 'C'
        val id = engine.hitTest(geo, x = 50f, y = 25f)
        assertNotNull(id)
        assertEquals('C'.code, geo.keyById(id!!)!!.keyDef.code)
    }

    // ── KeyGeometry.keyById ───────────────────────────────────────────────────

    @Test
    fun `keyById returns the correct key for a valid KeyId`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        val id  = KeyId(rowIndex = 0, keyIndex = 3)  // 'r'
        val key = geo.keyById(id)
        assertNotNull(key)
        assertEquals('r'.code, key!!.keyDef.code)
    }

    @Test
    fun `keyById returns null for an unknown KeyId`() {
        val geo = engine.layoutFor(twoRowLayout(), width = 400, height = 200)
        assertNull(geo.keyById(KeyId(rowIndex = 99, keyIndex = 99)))
    }

    // ── ResolvedKey geometry helpers ──────────────────────────────────────────

    @Test
    fun `ResolvedKey centerX and centerY are mid-points of the bounding rect`() {
        val key = ResolvedKey(
            id     = KeyId(0, 0),
            keyDef = KeyDef(code = 'a'.code, label = "a", width = 0.1f),
            left   = 10f,
            top    = 20f,
            right  = 110f,
            bottom = 70f,
        )
        assertEquals(60f,  key.centerX, 1e-3f)
        assertEquals(45f,  key.centerY, 1e-3f)
    }

    @Test
    fun `ResolvedKey contains returns true for the centre point`() {
        val key = ResolvedKey(
            id     = KeyId(0, 0),
            keyDef = KeyDef(code = 'a'.code, label = "a", width = 0.1f),
            left = 0f, top = 0f, right = 100f, bottom = 50f,
        )
        assertTrue(key.contains(50f, 25f))
    }

    @Test
    fun `ResolvedKey contains returns false for a point above the key`() {
        val key = ResolvedKey(
            id     = KeyId(0, 0),
            keyDef = KeyDef(code = 'a'.code, label = "a", width = 0.1f),
            left = 0f, top = 10f, right = 100f, bottom = 60f,
        )
        assertFalse(key.contains(50f, 5f))
    }

    // ── Multi-row coverage sanity ─────────────────────────────────────────────

    @Test
    fun `layoutFor with four equal rows assigns 25 percent height to each`() {
        val row = Row(listOf(key('a'.code, "a", 1.0f)))
        val def = LayoutDefinition(
            id        = "four",
            locale    = "en-US",
            direction = Direction.LTR,
            rows      = List(4) { row },
        )
        val geo = engine.layoutFor(def, width = 100, height = 200)
        assertEquals(50f, geo.rowHeight, 1e-3f)

        val row2Key = geo.keys.first { it.id.rowIndex == 2 }
        assertEquals(100f, row2Key.top,    1e-3f)
        assertEquals(150f, row2Key.bottom, 1e-3f)
    }
}
