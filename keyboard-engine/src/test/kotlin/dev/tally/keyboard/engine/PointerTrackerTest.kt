package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [PointerTracker] and [PointerTrackerPool].
 *
 * All tests are pure JVM — no Android types. The geometry used here matches a simple
 * two-column, two-row layout at a 200×100 viewport so the pixel arithmetic is obvious.
 *
 * Layout (200 wide × 100 tall, 2 rows × 2 keys each):
 *   Row 0:  key(0,0) = [0..100) × [0..50)     key(0,1) = [100..200) × [0..50)
 *   Row 1:  key(1,0) = [0..100) × [50..100)   key(1,1) = [100..200) × [50..100)
 */
class PointerTrackerTest {

    private lateinit var geometry: KeyGeometry

    @BeforeEach
    fun buildGeometry() {
        val keyDef = KeyDef(code = 'a'.code, label = "a", width = 0.5f)
        geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), keyDef, left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), keyDef, left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), keyDef, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), keyDef, left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )
    }

    // ── PointerTracker construction ───────────────────────────────────────────

    @Test
    fun construction_downOnKey_setsDownKeyAndCurrentKey() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)

        assertEquals(KeyId(0, 0), tracker.downKey)
        assertEquals(KeyId(0, 0), tracker.currentKey)
    }

    @Test
    fun construction_downInGap_nullDownKey() {
        val tracker = PointerTracker(pointerId = 1, downKey = null, geometry = geometry)

        assertNull(tracker.downKey)
        assertNull(tracker.currentKey)
    }

    // ── PointerTracker rollover via onMove ────────────────────────────────────

    @Test
    fun onMove_toAdjacentKey_updatesCurrentKey() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)

        // Slide to the right key
        val hit = tracker.onMove(150f, 25f)

        assertEquals(KeyId(0, 1), hit)
        assertEquals(KeyId(0, 1), tracker.currentKey)
    }

    @Test
    fun onMove_offBoard_retainsLastValidKey() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)

        // Move to a valid key first
        tracker.onMove(150f, 25f) // key (0,1)
        // Then slide off-board
        val hit = tracker.onMove(-50f, -50f)

        // Hit-test returns null (off-board), but currentKey retains (0,1)
        assertNull(hit)
        assertEquals(KeyId(0, 1), tracker.currentKey)
    }

    @Test
    fun onMove_returnsNull_whenPointInGap_butCurrentKeyRetained() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(1, 0), geometry = geometry)

        // Slide to a key so currentKey is set
        tracker.onMove(50f, 75f)  // still (1,0)

        // Then off-board
        val result = tracker.onMove(500f, 500f)

        assertNull(result)
        assertEquals(KeyId(1, 0), tracker.currentKey)
    }

    // ── PointerTracker onUp ───────────────────────────────────────────────────

    @Test
    fun onUp_returnsDownKey_whenNoRollover() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)

        val result = tracker.onUp()

        assertEquals(KeyId(0, 0), result)
    }

    @Test
    fun onUp_returnsRolledKey_afterMove() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)
        tracker.onMove(150f, 25f) // roll to (0,1)

        val result = tracker.onUp()

        assertEquals(KeyId(0, 1), result)
    }

    @Test
    fun onUp_returnsNull_whenDownKeyWasNull_andNoMove() {
        val tracker = PointerTracker(pointerId = 0, downKey = null, geometry = geometry)

        assertNull(tracker.onUp())
    }

    // ── PointerTracker slide-path recording ───────────────────────────────────

    @Test
    fun recordPoint_appendsToSlidePath() {
        val tracker = PointerTracker(pointerId = 0, downKey = KeyId(0, 0), geometry = geometry)

        tracker.recordPoint(10f, 20f)
        tracker.recordPoint(30f, 40f)

        assertEquals(2, tracker.slidePath.size)
        assertArrayEquals(floatArrayOf(10f, 20f), tracker.slidePath[0], 0.001f)
        assertArrayEquals(floatArrayOf(30f, 40f), tracker.slidePath[1], 0.001f)
    }

    // ── PointerTrackerPool ────────────────────────────────────────────────────

    @Test
    fun pool_initially_isEmpty() {
        val pool = PointerTrackerPool()

        assertTrue(pool.isEmpty())
        assertEquals(0, pool.size)
    }

    @Test
    fun pool_onDown_registersTracker() {
        val pool = PointerTrackerPool()

        val key = pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)

        assertEquals(KeyId(0, 0), key)
        assertEquals(1, pool.size)
        assertFalse(pool.isEmpty())
    }

    @Test
    fun pool_onDown_twoFingers_bothTracked() {
        val pool = PointerTrackerPool()

        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)   // key (0,0)
        pool.onDown(pointerId = 1, x = 150f, y = 25f, geometry = geometry)  // key (0,1)

        assertEquals(2, pool.size)
        assertEquals(KeyId(0, 0), pool.trackerFor(0)?.currentKey)
        assertEquals(KeyId(0, 1), pool.trackerFor(1)?.currentKey)
    }

    @Test
    fun pool_onMove_updatesCorrectTracker() {
        val pool = PointerTrackerPool()
        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)   // key (0,0)
        pool.onDown(pointerId = 1, x = 150f, y = 75f, geometry = geometry)  // key (1,1)

        // Pointer 0 slides to key (0,1)
        pool.onMove(pointerId = 0, x = 150f, y = 25f)

        assertEquals(KeyId(0, 1), pool.trackerFor(0)?.currentKey)
        // Pointer 1 is unchanged
        assertEquals(KeyId(1, 1), pool.trackerFor(1)?.currentKey)
    }

    @Test
    fun pool_onMove_unknownPointer_returnsNull() {
        val pool = PointerTrackerPool()

        val result = pool.onMove(pointerId = 99, x = 50f, y = 25f)

        assertNull(result)
    }

    @Test
    fun pool_onUp_removesTracker_returnsKey() {
        val pool = PointerTrackerPool()
        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)

        val key = pool.onUp(pointerId = 0)

        assertEquals(KeyId(0, 0), key)
        assertTrue(pool.isEmpty())
    }

    @Test
    fun pool_onUp_unknownPointer_returnsNull() {
        val pool = PointerTrackerPool()

        val result = pool.onUp(pointerId = 42)

        assertNull(result)
    }

    @Test
    fun pool_onUp_firstFinger_secondRemains() {
        val pool = PointerTrackerPool()
        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)
        pool.onDown(pointerId = 1, x = 150f, y = 75f, geometry = geometry)

        pool.onUp(pointerId = 0)

        assertEquals(1, pool.size)
        assertNull(pool.trackerFor(0))
        assertNotNull(pool.trackerFor(1))
    }

    @Test
    fun pool_onCancel_clearsAllTrackers() {
        val pool = PointerTrackerPool()
        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)
        pool.onDown(pointerId = 1, x = 150f, y = 75f, geometry = geometry)
        pool.onDown(pointerId = 2, x = 50f, y = 75f, geometry = geometry)

        pool.onCancel()

        assertTrue(pool.isEmpty())
        assertEquals(0, pool.size)
    }

    @Test
    fun pool_onDown_inGap_returnsNull_trackerStillCreated() {
        val pool = PointerTrackerPool()

        // Touch between rows (e.g., 200 × 200 viewport but geometry is only 100 tall)
        val key = pool.onDown(pointerId = 0, x = 50f, y = 200f, geometry = geometry)

        assertNull(key)
        // A tracker is still allocated so we handle the eventual UP gracefully
        assertEquals(1, pool.size)
    }

    // ── Rollover without dropping keys ────────────────────────────────────────

    @Test
    fun rollover_fingerSlidesCrossesRowBoundary_emitsDestinationKey() {
        val pool = PointerTrackerPool()
        pool.onDown(pointerId = 0, x = 50f, y = 25f, geometry = geometry)  // key (0,0)

        // Slide down into row 1
        pool.onMove(0, 50f, 75f)

        val committed = pool.onUp(0)

        assertEquals(KeyId(1, 0), committed)
    }

    @Test
    fun twoThumbTyping_independentKeys_neitherDropped() {
        val pool = PointerTrackerPool()

        // Finger 1: key (0,0)
        val k1down = pool.onDown(0, 50f, 25f, geometry)
        // Finger 2 comes down while finger 1 is still held: key (0,1)
        val k2down = pool.onDown(1, 150f, 25f, geometry)
        // Finger 1 lifts
        val k1up = pool.onUp(0)
        // Finger 2 lifts
        val k2up = pool.onUp(1)

        assertEquals(KeyId(0, 0), k1down, "First key down must register")
        assertEquals(KeyId(0, 1), k2down, "Second key down must register independently")
        assertEquals(KeyId(0, 0), k1up,   "First key up must emit the correct key")
        assertEquals(KeyId(0, 1), k2up,   "Second key up must emit the correct key")
    }
}
