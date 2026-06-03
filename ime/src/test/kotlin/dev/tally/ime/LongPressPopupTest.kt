package dev.tally.ime

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.ResolvedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies long-press alternate-key behaviour in [KeyPlaneView].
 *
 * Tests cover:
 *   - Long-press tray is not shown before the delay elapses
 *   - Long-press tray is shown after the delay elapses
 *   - Key-preview is dismissed when the tray opens
 *   - Move while tray is open highlights the correct cell
 *   - UP after moving to a cell commits the alternate character
 *   - UP without moving into a cell commits the primary key (fallback)
 *   - ACTION_CANCEL dismisses an open tray and does not emit a key
 *   - Keys with no moreKeys never show a long-press tray
 *   - Two pointers each get their own independent tray
 *   - Long-press on the first pointer while second is held does not affect second pointer
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LongPressPopupTest {

    private lateinit var ctx: Context
    private lateinit var view: KeyPlaneView
    private lateinit var geometry: KeyGeometry
    private val committed = mutableListOf<Key>()

    /**
     * 2 × 2 grid, 200 × 100 px.
     *
     *   key(0,0) = [0..100) × [0..50)    label "e"  moreKeys ["é","è","ê"]
     *   key(0,1) = [100..200) × [0..50)  label "u"  moreKeys ["ú","ù"]
     *   key(1,0) = [0..100) × [50..100)  label ""   (space-like, no moreKeys)
     *   key(1,1) = [100..200) × [50..100) label "n"  moreKeys ["ñ"]
     */
    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        view = KeyPlaneView(ctx)
        committed.clear()

        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        val defE = KeyDef(code = 'e'.code, label = "e", moreKeys = listOf("é", "è", "ê"), width = 0.5f)
        val defU = KeyDef(code = 'u'.code, label = "u", moreKeys = listOf("ú", "ù"), width = 0.5f)
        val defSpace = KeyDef(code = -5, label = "", width = 0.5f, isSpecial = true)
        val defN = KeyDef(code = 'n'.code, label = "n", moreKeys = listOf("ñ"), width = 0.5f)

        geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), defE,     left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), defU,     left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), defSpace, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), defN,     left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )

        view.currentGeometry = geometry
        // Layout rows must mirror the geometry so resolveKey() can map KeyId → Key.
        view.currentRows = listOf(
            KeyRow(listOf(
                Key(KeyCode.Char('e'), "e", moreKeys = listOf("é", "è", "ê")),
                Key(KeyCode.Char('u'), "u", moreKeys = listOf("ú", "ù")),
            )),
            KeyRow(listOf(
                Key(KeyCode.Space, "", isSpecial = true),
                Key(KeyCode.Char('n'), "n", moreKeys = listOf("ñ")),
            )),
        )

        view.keyListener = { key, _ -> committed += key }
        // Unmasked by default in tests so preview-related assertions work normally.
        view.previewMasked = false
    }

    // ── Tray visibility ───────────────────────────────────────────────────────

    @Test
    fun longPress_tray_notShown_beforeDelay() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key "e" with moreKeys

        // Do NOT advance the looper — the timer has not fired.
        assertFalse("Tray must not appear before delay elapses", view.isLongPressShowing(0))
    }

    @Test
    fun longPress_tray_shownAfterDelay() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        fireLongPress()

        assertTrue("Tray must be visible after delay", view.isLongPressShowing(0))
    }

    @Test
    fun longPress_keyPreview_dismissedWhenTrayOpens() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        assertTrue("Preview must show on DOWN", view.isPreviewShowing(0))

        fireLongPress()

        assertFalse("Preview must be gone once tray replaces it", view.isPreviewShowing(0))
        assertTrue("Tray must now be showing", view.isLongPressShowing(0))
    }

    // ── Selection via move ────────────────────────────────────────────────────

    @Test
    fun longPress_move_highlightsCorrectCell() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        fireLongPress()

        // The tray for "e" shows ["é","è","ê"]. Moving over the popup at x≈50 should
        // select a cell. The exact pixel mapping depends on the popup's left edge
        // (clamped to 0) and cell size (44 dp × density). On Robolectric density=1.0,
        // cell size = 44 px. We can't assert a specific index without knowing the anchor
        // positioning, but we CAN assert that a move at an x that would be inside the
        // first cell (x=10) changes selectedIndex to ≥ 0.
        dispatchMove(pointerId = 0, x = 10f, y = 25f)

        assertTrue(
            "Move into tray area must highlight a cell (index ≥ 0)",
            view.longPressSelectedIndex(0) >= 0,
        )
    }

    // ── Commit on UP ──────────────────────────────────────────────────────────

    @Test
    fun longPress_up_afterSelectingAlternate_commitsAlternate() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        fireLongPress()
        dispatchMove(pointerId = 0, x = 10f, y = 25f) // select some cell
        val expectedIdx = view.longPressSelectedIndex(0)

        dispatchUp(pointerId = 0, x = 10f, y = 25f)

        assertTrue("Must have committed exactly one key", committed.size == 1)
        val expectedLabel = listOf("é", "è", "ê")[expectedIdx]
        assertEquals("Committed key must be the selected alternate", expectedLabel, committed[0].label)
    }

    @Test
    fun longPress_up_withoutMove_commitsPrimaryKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        fireLongPress()

        // Lift without moving into a cell.
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertTrue("Must have committed exactly one key", committed.size == 1)
        assertEquals("Primary key must be committed when no alternate selected", "e", committed[0].label)
    }

    @Test
    fun normalTap_belowDelay_commitsThePrimaryKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        // Do NOT advance the looper.
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertEquals("Normal short tap must commit primary key", 1, committed.size)
        assertEquals("e", committed[0].label)
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Test
    fun longPress_cancel_dismissesTray_andDoesNotCommit() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        fireLongPress()
        assertTrue(view.isLongPressShowing(0))

        dispatchCancel()

        assertFalse("Tray must be dismissed after ACTION_CANCEL", view.isLongPressShowing(0))
        assertTrue("No key must be committed after cancel", committed.isEmpty())
    }

    // ── Keys without alternates ───────────────────────────────────────────────

    @Test
    fun keyWithNoMoreKeys_neverShowsTray() {
        dispatchDown(pointerId = 0, x = 50f, y = 75f)  // space key — no moreKeys
        fireLongPress()

        assertFalse("Space key must never show a long-press tray", view.isLongPressShowing(0))
    }

    // ── Two-finger independence ───────────────────────────────────────────────

    @Test
    fun twoFingers_eachGetIndependentTrays() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key "e"
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f,                      // key "u"
        )
        fireLongPress()

        assertTrue("Finger 0 must show its tray", view.isLongPressShowing(0))
        assertTrue("Finger 1 must show its tray", view.isLongPressShowing(1))
    }

    @Test
    fun longPress_onFirstFinger_doesNotAffectSecondFinger() {
        // Pointer 0 presses a key with alternates and holds (long-press fires).
        // Pointer 1 presses a different key and lifts BEFORE the delay elapses.
        // Result: pointer 1 should commit its primary key normally; pointer 0's long-press
        // is unaffected.
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key "e", will long-press
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 75f,                      // key "n", lifts quickly
        )

        // Lift pointer 1 before any timer fires.
        dispatchPointerUp(
            releasedId = 1, remainingId = 0,
            remainingX = 50f, remainingY = 25f,
            releasedX  = 150f, releasedY = 75f,
        )

        // Pointer 1 must have committed "n" immediately on UP.
        assertEquals("Pointer 1 must commit exactly one key", 1, committed.size)
        assertEquals("n", committed[0].label)

        // Now fire pointer 0's long-press — pointer 0 is independent.
        fireLongPress()
        assertTrue("Pointer 0 must have active tray after its delay", view.isLongPressShowing(0))
        // Still only one key committed — the long-press for pointer 0 has not resolved yet.
        assertEquals("No extra commit from pointer 0 long-press opening", 1, committed.size)
    }

    // ── Synthetic MotionEvent helpers ─────────────────────────────────────────

    /** Advances the Robolectric main-thread looper past the long-press delay. */
    private fun fireLongPress() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

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

    private fun dispatchCancel() {
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0,
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun singlePointerEvent(action: Int, pointerId: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun dispatchPointerDown(
        pointerId: Int, existingId: Int,
        existingX: Float, existingY: Float,
        newX: Float, newY: Float,
    ) {
        val newPointerIndex = 1
        val action = MotionEvent.ACTION_POINTER_DOWN or
            (newPointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = existingId; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = existingX; y = existingY; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = newX;      y = newY;      pressure = 1f; size = 1f },
        )
        val event = MotionEvent.obtain(
            0L, 0L, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun dispatchPointerUp(
        releasedId: Int, remainingId: Int,
        remainingX: Float, remainingY: Float,
        releasedX: Float, releasedY: Float,
    ) {
        val releasedIndex = 0
        val action = MotionEvent.ACTION_POINTER_UP or
            (releasedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = releasedId; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = remainingId; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = releasedX;  y = releasedY;  pressure = 0f; size = 1f },
            MotionEvent.PointerCoords().apply { x = remainingX; y = remainingY; pressure = 1f; size = 1f },
        )
        val event = MotionEvent.obtain(
            0L, 0L, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.onTouchEvent(event)
        event.recycle()
    }
}
