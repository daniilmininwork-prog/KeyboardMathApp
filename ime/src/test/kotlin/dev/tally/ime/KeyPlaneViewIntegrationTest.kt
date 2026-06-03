package dev.tally.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end smoke test for [KeyPlaneView] driven exactly the way the IME service drives it.
 *
 * Unlike [PointerTrackerDispatchTest] / [CursorGestureTest], this test deliberately does **not**
 * inject [KeyPlaneView.currentGeometry] by hand — it only sets [KeyPlaneView.currentRows] and lets
 * the real measure → layout → buildGeometry path produce the geometry. Injecting geometry directly
 * is what masked the original "blank bar / dead touches" bug (geometry stayed null in production
 * because nothing built it), so the regression guard here asserts the view builds its own geometry
 * after layout and then types from coordinates derived from that geometry.
 *
 * Covered:
 *   - After measure() + layout(), currentGeometry is non-null (the exact regression).
 *   - A DOWN/UP at the centre of a real key commits the matching [Key].
 *   - onDraw runs against a real Canvas without throwing and the geometry it iterates is non-empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPlaneViewIntegrationTest {

    private lateinit var view: KeyPlaneView
    private val committedKeys = mutableListOf<Key>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)
        view.keyListener = { key, _ -> committedKeys += key }

        // Real wiring: set the rows the way the service does and let the view size + lay out
        // itself. No currentGeometry is assigned by the test — buildGeometry must run on layout.
        view.currentRows = KeyboardLayout.ALPHA_LOWER

        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, VIEW_WIDTH, view.measuredHeight)
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    @Test
    fun afterLayout_geometryIsBuiltFromRows() {
        // The original bug: currentGeometry was never assigned in production, so it stayed null,
        // the bar drew blank, and every touch was discarded. Layout must now populate it.
        val geometry = view.currentGeometry
        assertNotNull("currentGeometry must be built after measure()+layout()", geometry)
        assertTrue("geometry must contain a key per cell in the layout", geometry!!.keys.isNotEmpty())
        // One ResolvedKey per Key across all rows — geometry and rows stay in lock-step.
        assertEquals(
            KeyboardLayout.ALPHA_LOWER.sumOf { it.keys.size },
            geometry.keys.size,
        )
    }

    // ── Real type path ────────────────────────────────────────────────────────

    @Test
    fun tapCentreOfKey_commitsThatKey() {
        val geometry = view.currentGeometry!!

        // 'q' is the first key of the first row in ALPHA_LOWER; resolve its rect from the geometry
        // the view built (not a hand-made one) so the coordinates we tap are the production ones.
        val resolved = geometry.keys.first()
        val expected = KeyboardLayout.ALPHA_LOWER[0].keys[0]
        assertEquals("sanity: first key is 'q'", KeyCode.Char('q'), expected.code)

        dispatchDown(resolved.centerX, resolved.centerY)
        dispatchUp(resolved.centerX, resolved.centerY)

        assertEquals(1, committedKeys.size)
        assertEquals(KeyCode.Char('q'), committedKeys[0].code)
    }

    @Test
    fun tapCentreOfSecondRowKey_commitsThatKey() {
        val geometry = view.currentGeometry!!

        // 'a' is row 1, key 0 (the ASDF… row, offset half a unit). Pick it via its KeyId so the
        // (rowIndex, keyIndex) → Key resolution path is exercised, not just the first list element.
        val resolved = geometry.keys.first { it.id.rowIndex == 1 && it.id.keyIndex == 0 }
        val expected = KeyboardLayout.ALPHA_LOWER[1].keys[0]
        assertEquals("sanity: row 1 key 0 is 'a'", KeyCode.Char('a'), expected.code)

        dispatchDown(resolved.centerX, resolved.centerY)
        dispatchUp(resolved.centerX, resolved.centerY)

        assertEquals(1, committedKeys.size)
        assertEquals(KeyCode.Char('a'), committedKeys[0].code)
    }

    // ── Draw path ─────────────────────────────────────────────────────────────

    @Test
    fun draw_overRealCanvas_runsWithoutThrowingAndHasKeysToDraw() {
        val geometry = view.currentGeometry!!
        // The draw loop iterates geometry.keys; a non-empty list means real key faces are painted
        // (the blank-bar bug drew only the background because no keys were ever drawn).
        assertTrue(geometry.keys.isNotEmpty())

        val bmp = Bitmap.createBitmap(
            VIEW_WIDTH,
            view.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        // view.draw routes through onDraw; must not throw with a populated geometry.
        view.draw(Canvas(bmp))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dispatchDown(x: Float, y: Float) {
        val event = singlePointerEvent(MotionEvent.ACTION_DOWN, x, y)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun dispatchUp(x: Float, y: Float) {
        val event = singlePointerEvent(MotionEvent.ACTION_UP, x, y)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun singlePointerEvent(action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private companion object {
        // A typical phone-portrait keyboard width in px; large enough that every key gets a
        // touchable rect at Robolectric's 1× density.
        const val VIEW_WIDTH = 1080
    }
}
