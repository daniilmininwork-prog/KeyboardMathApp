package dev.tally.ime

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.DefaultLayoutEngine
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.LayoutDefinition
import dev.tally.keyboard.engine.Direction
import dev.tally.keyboard.engine.ResolvedKey
import dev.tally.keyboard.engine.Row
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [KeyPlaneView]'s multitouch dispatch and [PointerTrackerPool] integration.
 *
 * Uses synthetic [MotionEvent]s built with [MotionEvent.obtain] so no emulator is
 * needed. Tests specifically cover:
 *   - Single-finger press → key emitted on UP
 *   - Two simultaneous fingers → each emits its own key (no drops)
 *   - Rollover: finger slides from one key to another, emits destination on UP
 *   - ACTION_CANCEL tears down all trackers without emitting keys
 *
 * The layout used here is a simple 2-column × 2-row geometry at 200 × 100 px so
 * the hit-test arithmetic is obvious and verifiable by inspection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PointerTrackerDispatchTest {

    private lateinit var view: KeyPlaneView
    private val emittedKeys = mutableListOf<Key>()

    /**
     * Minimal KeyGeometry: 2 rows × 2 keys, 200 wide × 100 tall.
     *
     *   key(0,0) = [0..100) × [0..50)     key(0,1) = [100..200) × [0..50)
     *   key(1,0) = [0..100) × [50..100)   key(1,1) = [100..200) × [50..100)
     */
    private lateinit var geometry: KeyGeometry

    /** Matching KeyRow list so resolveKey can find real Key objects. */
    private lateinit var rows: List<KeyRow>

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)

        // Force a measured size so the view has a valid coordinate space.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        val kDef = KeyDef(code = 'a'.code, label = "a", width = 0.5f)
        geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), kDef, left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), kDef, left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), kDef, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), kDef, left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )

        // Build matching KeyRows with real Key objects so resolveKey succeeds.
        val key00 = Key(KeyCode.Char('a'), "a")
        val key01 = Key(KeyCode.Char('b'), "b")
        val key10 = Key(KeyCode.Char('c'), "c")
        val key11 = Key(KeyCode.Char('d'), "d")
        rows = listOf(
            KeyRow(listOf(key00, key01)),
            KeyRow(listOf(key10, key11)),
        )

        view.currentGeometry = geometry
        view.currentRows = rows
        view.keyListener = { key -> emittedKeys += key }
    }

    // ── Single-finger basics ──────────────────────────────────────────────────

    @Test
    fun singleFinger_down_thenUp_emitsOneKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key(0,0)
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertEquals(1, emittedKeys.size)
        assertEquals(KeyCode.Char('a'), emittedKeys[0].code)
    }

    @Test
    fun singleFinger_upInGap_emitsNothing_whenNoPriorMove() {
        // Start in a gap (outside the 200×100 geometry)
        dispatchDown(pointerId = 0, x = 50f, y = 150f)  // off-board
        dispatchUp(pointerId = 0, x = 50f, y = 150f)

        // downKey was null; currentKey is null; onUp returns null → no emit
        assertTrue(emittedKeys.isEmpty())
    }

    @Test
    fun singleFinger_noGeometry_touchIgnored() {
        view.currentGeometry = null
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertTrue(emittedKeys.isEmpty())
    }

    // ── Rollover ──────────────────────────────────────────────────────────────

    @Test
    fun rollover_slideToAdjacentKey_emitsMoveDestination() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)    // key(0,0)
        dispatchMove(pointerId = 0, x = 150f, y = 25f)   // slides to key(0,1)
        dispatchUp(pointerId = 0, x = 150f, y = 25f)

        assertEquals(1, emittedKeys.size)
        assertEquals(KeyCode.Char('b'), emittedKeys[0].code)  // key(0,1) = 'b'
    }

    @Test
    fun rollover_acrossRows_emitsBottomKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)    // key(0,0)
        dispatchMove(pointerId = 0, x = 50f, y = 75f)    // slides to key(1,0)
        dispatchUp(pointerId = 0, x = 50f, y = 75f)

        assertEquals(1, emittedKeys.size)
        assertEquals(KeyCode.Char('c'), emittedKeys[0].code)  // key(1,0) = 'c'
    }

    // ── Two-thumb typing ──────────────────────────────────────────────────────

    @Test
    fun twoFingers_simultaneousPress_bothKeysEmitted() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)    // key(0,0) = 'a'
        dispatchPointerDown(pointerId = 1, existingId = 0, existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f)                      // key(0,1) = 'b'

        dispatchPointerUp(releasedId = 0, remainingId = 1, remainingX = 150f, remainingY = 25f,
            releasedX = 50f, releasedY = 25f)             // finger 0 lifts
        dispatchUp(pointerId = 1, x = 150f, y = 25f)     // finger 1 lifts

        assertEquals(2, emittedKeys.size)
        val codes = emittedKeys.map { it.code }.toSet()
        assertTrue(codes.contains(KeyCode.Char('a')))
        assertTrue(codes.contains(KeyCode.Char('b')))
    }

    @Test
    fun twoFingers_activePointerCount_trackedCorrectly() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        assertEquals(1, view.activePointerCount())

        dispatchPointerDown(pointerId = 1, existingId = 0, existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f)
        assertEquals(2, view.activePointerCount())

        dispatchPointerUp(releasedId = 0, remainingId = 1, remainingX = 150f, remainingY = 25f,
            releasedX = 50f, releasedY = 25f)
        assertEquals(1, view.activePointerCount())

        dispatchUp(pointerId = 1, x = 150f, y = 25f)
        assertEquals(0, view.activePointerCount())
    }

    // ── ACTION_CANCEL ─────────────────────────────────────────────────────────

    @Test
    fun actionCancel_clearsAllTrackers_emitsNoKeys() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchPointerDown(pointerId = 1, existingId = 0, existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f)

        dispatchCancel()

        assertEquals(0, view.activePointerCount())
        assertTrue("ACTION_CANCEL must not emit keys", emittedKeys.isEmpty())
    }

    @Test
    fun actionCancel_afterCancel_freshGestureWorks() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchCancel()

        // Fresh gesture after the cancel
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertEquals(1, emittedKeys.size)
    }

    // ── Synthetic MotionEvent helpers ─────────────────────────────────────────

    private fun dispatchDown(pointerId: Int, x: Float, y: Float) {
        val event = singlePointerEvent(MotionEvent.ACTION_DOWN, pointerId, x, y)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun dispatchUp(pointerId: Int, x: Float, y: Float) {
        val event = singlePointerEvent(MotionEvent.ACTION_UP, pointerId, x, y)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun dispatchMove(pointerId: Int, x: Float, y: Float) {
        val event = singlePointerEvent(MotionEvent.ACTION_MOVE, pointerId, x, y)
        view.onTouchEvent(event)
        event.recycle()
    }

    /**
     * Builds a single-pointer [MotionEvent] that correctly encodes [pointerId] in its
     * pointer properties. The basic [MotionEvent.obtain] overload always assigns pointer
     * ID 0, which breaks multipointer tests when a second pointer (id != 0) needs to
     * send a standalone UP event.
     */
    private fun singlePointerEvent(action: Int, pointerId: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun dispatchCancel() {
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0,
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        view.onTouchEvent(event)
        event.recycle()
    }

    /**
     * Synthesizes an ACTION_POINTER_DOWN event with two active pointers.
     *
     * The MotionEvent API requires all active pointers to be listed in the event.
     * [pointerId] is the new pointer; [existingId] is the already-active pointer.
     */
    private fun dispatchPointerDown(
        pointerId: Int, existingId: Int,
        existingX: Float, existingY: Float,
        newX: Float, newY: Float,
    ) {
        val newPointerIndex = 1  // second pointer is always at index 1 for our two-pointer tests
        val action = MotionEvent.ACTION_POINTER_DOWN or (newPointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = existingId; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = pointerId;  toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = existingX; y = existingY; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = newX;      y = newY;      pressure = 1f; size = 1f },
        )
        val event = MotionEvent.obtain(0L, 0L, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    /**
     * Synthesizes an ACTION_POINTER_UP event where [releasedId] lifts.
     * [remainingId] remains active.
     */
    private fun dispatchPointerUp(
        releasedId: Int, remainingId: Int,
        remainingX: Float, remainingY: Float,
        releasedX: Float, releasedY: Float,
    ) {
        val releasedIndex = 0  // put the released pointer at index 0 for simplicity
        val action = MotionEvent.ACTION_POINTER_UP or (releasedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = releasedId;  toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = remainingId; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = releasedX;  y = releasedY;  pressure = 0f; size = 1f },
            MotionEvent.PointerCoords().apply { x = remainingX; y = remainingY; pressure = 1f; size = 1f },
        )
        val event = MotionEvent.obtain(0L, 0L, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(event)
        event.recycle()
    }
}
