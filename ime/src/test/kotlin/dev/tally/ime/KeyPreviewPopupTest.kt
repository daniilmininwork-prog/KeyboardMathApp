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

/**
 * Verifies key-preview popup lifecycle in [KeyPlaneView].
 *
 * Tests cover:
 *   - Preview shown on ACTION_DOWN for a character key
 *   - Preview dismissed on ACTION_UP
 *   - Preview dismissed on ACTION_CANCEL
 *   - Preview not shown when [KeyPlaneView.previewMasked] is true
 *   - Preview not shown for keys with empty labels (space, enter, special keys)
 *   - Two simultaneous fingers each get independent previews
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPreviewPopupTest {

    private lateinit var ctx: Context
    private lateinit var view: KeyPlaneView
    private lateinit var geometry: KeyGeometry

    /**
     * Minimal 2 × 2 grid, 200 × 100 px.
     *
     *   key(0,0) = [0..100) × [0..50)    label "a"
     *   key(0,1) = [100..200) × [0..50)  label "b"
     *   key(1,0) = [0..100) × [50..100)  label "" (empty — space-like)
     *   key(1,1) = [100..200) × [50..100) label "c"
     */
    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        view = KeyPlaneView(ctx)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        val defA = KeyDef(code = 'a'.code, label = "a", width = 0.5f)
        val defB = KeyDef(code = 'b'.code, label = "b", width = 0.5f)
        val defEmpty = KeyDef(code = -5, label = "", width = 0.5f, isSpecial = true)
        val defC = KeyDef(code = 'c'.code, label = "c", width = 0.5f)

        geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), defA, left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), defB, left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), defEmpty, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), defC, left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )

        view.currentGeometry = geometry
        // Wire a keyListener so the view does not ignore UP events.
        view.keyListener = { _, _ -> }
        // Default to unmasked so preview tests exercise the normal show/hide path.
        // Tests that verify masking behaviour set previewMasked = true themselves.
        view.previewMasked = false
    }

    // ── Basic show / hide ─────────────────────────────────────────────────────

    @Test
    fun preview_shownOnDown_forCharacterKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key(0,0) = "a"

        assertTrue("Preview must be visible after DOWN on a labelled key",
            view.isPreviewShowing(0))
    }

    @Test
    fun preview_dismissedOnUp() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        assertFalse("Preview must be gone after UP", view.isPreviewShowing(0))
    }

    @Test
    fun preview_dismissedOnCancel() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchCancel()

        assertFalse("Preview must be gone after ACTION_CANCEL", view.isPreviewShowing(0))
    }

    // ── Masked fields ─────────────────────────────────────────────────────────

    @Test
    fun preview_shownRedacted_whenPreviewMasked() {
        view.previewMasked = true
        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key(0,0) = "a"

        // Masked fields keep the tactile bubble for feedback but must never render the glyph,
        // so the bubble shows a neutral dot rather than the pressed key's character.
        assertTrue("Bubble must still appear in masked fields for tactile feedback",
            view.isPreviewShowing(0))
        assertEquals("Masked bubble must not leak the typed glyph",
            "•", view.previewText(0).toString())
    }

    @Test
    fun preview_showsGlyph_whenUnmasked() {
        // The default (unmasked) field renders the real key glyph in the bubble.
        dispatchDown(pointerId = 0, x = 50f, y = 25f)  // key(0,0) = "a"

        assertTrue(view.isPreviewShowing(0))
        assertEquals("a", view.previewText(0).toString())
    }

    @Test
    fun preview_showsGlyphAgain_afterMaskLifted() {
        view.previewMasked = true
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchUp(pointerId = 0, x = 50f, y = 25f)

        // Lift the mask (e.g., user moved to a non-password field): the glyph returns.
        view.previewMasked = false
        dispatchDown(pointerId = 0, x = 50f, y = 25f)

        assertTrue("Preview should appear once mask is lifted", view.isPreviewShowing(0))
        assertEquals("Unmasked bubble shows the real glyph again",
            "a", view.previewText(0).toString())
    }

    // ── Empty-label keys ──────────────────────────────────────────────────────

    @Test
    fun preview_notShown_forEmptyLabelKey() {
        dispatchDown(pointerId = 0, x = 50f, y = 75f)  // key(1,0) — empty label

        assertFalse("No preview for keys with no displayable label", view.isPreviewShowing(0))
    }

    // ── Two-finger simultaneous previews ──────────────────────────────────────

    @Test
    fun twoFingers_eachGetIndependentPreviews() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)   // key(0,0)
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f,                      // key(0,1)
        )

        assertTrue("Finger 0 must show its own preview", view.isPreviewShowing(0))
        assertTrue("Finger 1 must show its own preview", view.isPreviewShowing(1))
    }

    @Test
    fun twoFingers_upDismissesOnlyThatFinger() {
        dispatchDown(pointerId = 0, x = 50f, y = 25f)
        dispatchPointerDown(
            pointerId = 1, existingId = 0,
            existingX = 50f, existingY = 25f,
            newX = 150f, newY = 25f,
        )

        dispatchPointerUp(
            releasedId = 0, remainingId = 1,
            remainingX = 150f, remainingY = 25f,
            releasedX = 50f, releasedY = 25f,
        )

        assertFalse("Finger 0 preview must be dismissed", view.isPreviewShowing(0))
        assertTrue("Finger 1 preview must still be showing", view.isPreviewShowing(1))
    }

    // ── Off-board press ───────────────────────────────────────────────────────

    @Test
    fun preview_notShown_forOffBoardPress() {
        dispatchDown(pointerId = 0, x = 50f, y = 150f)  // outside the 200 × 100 geometry

        assertFalse("No preview when finger lands in a gap", view.isPreviewShowing(0))
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

    private fun dispatchCancel() {
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0,
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
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

    private fun dispatchPointerUp(
        releasedId: Int, remainingId: Int,
        remainingX: Float, remainingY: Float,
        releasedX: Float, releasedY: Float,
    ) {
        val releasedIndex = 0
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
