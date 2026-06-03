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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the press-feedback state on [KeyPlaneView] (Phase 0).
 *
 * The key bed used to paint nothing on finger-down, so a held key looked dead. These tests pin the
 * fix: a DOWN tints the pressed key (and schedules a repaint), an UP clears it, ACTION_CANCEL clears
 * every press, rollover moves the tint with the finger, and the preview default is fail-open so the
 * first pre-field state still feels alive.
 *
 * Geometry mirrors [KeyPreviewPopupTest]: a 2 × 2 grid, 200 × 100 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PressedKeyFeedbackTest {

    private lateinit var view: KeyPlaneView

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        val defA = KeyDef(code = 'a'.code, label = "a", width = 0.5f)
        val defB = KeyDef(code = 'b'.code, label = "b", width = 0.5f)
        val defC = KeyDef(code = 'c'.code, label = "c", width = 0.5f)
        val defD = KeyDef(code = 'd'.code, label = "d", width = 0.5f)

        view.currentGeometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), defA, left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), defB, left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), defC, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), defD, left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )
        view.keyListener = { _, _ -> }
    }

    // ── Fail-open preview default ──────────────────────────────────────────────

    @Test
    fun previewMasked_defaultsToFalse() {
        // A freshly constructed view (before any field entry) must not redact previews, so the
        // pre-field state still feels alive. onStartInputView raises this for secure fields.
        assertFalse("previewMasked must default to false (fail-open)", KeyPlaneView(view.context).previewMasked)
    }

    // ── DOWN populates + invalidates ───────────────────────────────────────────

    @Test
    fun down_marksKeyPressed_andInvalidates() {
        shadowOf(view).wasInvalidated()  // clear any pending flag from layout

        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key(0,0)

        assertTrue("DOWN must tint the pressed key", view.isKeyPressed(KeyId(0, 0)))
        assertEquals(1, view.pressedKeyCount())
        assertTrue("DOWN must request a repaint", shadowOf(view).wasInvalidated())
    }

    @Test
    fun down_offBoard_marksNothing() {
        dispatchDown(pointerId = 0, x = 50f, y = 150f)  // below the 100px-tall geometry

        assertEquals("A press in the gap tints no key", 0, view.pressedKeyCount())
    }

    // ── UP clears + invalidates ────────────────────────────────────────────────

    @Test
    fun up_clearsPressedKey_andInvalidates() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        shadowOf(view).wasInvalidated()  // clear the DOWN's flag

        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertFalse("UP must clear the pressed tint", view.isKeyPressed(KeyId(0, 0)))
        assertEquals(0, view.pressedKeyCount())
        assertTrue("UP must request a repaint", shadowOf(view).wasInvalidated())
    }

    // ── CANCEL clears everything ───────────────────────────────────────────────

    @Test
    fun cancel_clearsAllPressedKeys() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key(0,0)
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f,                      // key(0,1)
        )
        assertEquals(2, view.pressedKeyCount())

        dispatchCancel()

        assertEquals("ACTION_CANCEL must clear every press", 0, view.pressedKeyCount())
    }

    // ── Two fingers ────────────────────────────────────────────────────────────

    @Test
    fun twoFingers_eachTintTheirOwnKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key(0,0)
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f,                      // key(0,1)
        )

        assertTrue(view.isKeyPressed(KeyId(0, 0)))
        assertTrue(view.isKeyPressed(KeyId(0, 1)))
        assertEquals(2, view.pressedKeyCount())
    }

    // ── Rollover ───────────────────────────────────────────────────────────────

    @Test
    fun rollover_movesTintToNewKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key(0,0)
        assertTrue(view.isKeyPressed(KeyId(0, 0)))

        dispatchMove(pointerId = 0, x = 150f, y = 25f)  // slide onto key(0,1)

        assertFalse("Old key must release its tint on rollover", view.isKeyPressed(KeyId(0, 0)))
        assertTrue("New key must take the tint on rollover", view.isKeyPressed(KeyId(0, 1)))
        assertEquals(1, view.pressedKeyCount())
    }

    // ── Synthetic MotionEvent helpers (mirrors KeyPreviewPopupTest) ────────────

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
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        view.onTouchEvent(event)
        event.recycle()
    }

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

    private fun dispatchPointerDown(
        pointerId: Int, existingId: Int,
        existingX: Float, existingY: Float,
        newX: Float, newY: Float,
    ) {
        val newPointerIndex = 1
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
}
