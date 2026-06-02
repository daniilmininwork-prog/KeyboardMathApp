package dev.tally.ime

import android.content.Context
import android.view.MotionEvent
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
 * Verifies the space-bar cursor-control gesture wired in [KeyPlaneView] (T4.4).
 *
 * Acceptance criteria checked here:
 *   - A short tap on space still commits the space character (dead zone not crossed).
 *   - A horizontal swipe beyond the dead zone activates the gesture and fires
 *     [KeyPlaneView.cursorStepListener] with the correct step count.
 *   - No space character is committed when the gesture was active.
 *   - Rightward swipes produce positive steps; leftward swipes produce negative steps.
 *   - Multiple move events accumulate correctly (delta, not cumulative total).
 *   - Selection mode is passed through from [KeyPlaneView.cursorSelectMode].
 *   - ACTION_CANCEL resets the gesture — subsequent touches start fresh.
 *   - Normal typing on non-space keys is unaffected.
 *
 * Geometry: one space key at x=[100..300) occupying the full view height (100 px).
 * One character key at x=[0..100) in the same row for non-space control tests.
 * View is 300 px wide × 100 px tall with Robolectric's default 1× mdpi density.
 * Dead zone = 24 px (24 dp × 1 = 24 px at 1× density).
 * Step = 16 px (16 dp × 1 = 16 px at 1× density).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CursorGestureTest {

    private lateinit var view: KeyPlaneView

    private val committedKeys = mutableListOf<Key>()
    private val cursorSteps   = mutableListOf<Int>()
    private val selectFlags   = mutableListOf<Boolean>()

    // Space key occupies x=[100..300) y=[0..100)
    private val spaceX = 200f
    private val spaceY = 50f

    // Character key occupies x=[0..100) y=[0..100)
    private val charX = 50f
    private val charY = 50f

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)

        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 100)

        val charDef  = KeyDef(code = 'a'.code, label = "a",  width = 1f / 3f)
        val spaceDef = KeyDef(code = 0,         label = " ",  width = 2f / 3f)

        val geometry = KeyGeometry(
            viewportWidth  = 300,
            viewportHeight = 100,
            rowHeight      = 100f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), charDef,  left =   0f, top = 0f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(0, 1), spaceDef, left = 100f, top = 0f, right = 300f, bottom = 100f),
            ),
        )
        val rows = listOf(
            KeyRow(listOf(
                Key(KeyCode.Char('a'), "a"),
                Key(KeyCode.Space, "", widthUnits = 2f),
            )),
        )

        view.currentGeometry = geometry
        view.currentRows     = rows
        view.keyListener     = { key -> committedKeys += key }
        view.cursorStepListener = { steps, select ->
            cursorSteps += steps
            selectFlags += select
        }

        committedKeys.clear()
        cursorSteps.clear()
        selectFlags.clear()
    }

    // ── Normal space tap ──────────────────────────────────────────────────────

    @Test
    fun shortTap_onSpace_commitsSingleSpaceCharacter() {
        dispatchDown(spaceX, spaceY)
        // No move — stays within the dead zone.
        dispatchUp(spaceX, spaceY)

        assertEquals("Space tap must commit exactly one key", 1, committedKeys.size)
        assertEquals(KeyCode.Space, committedKeys[0].code)
        assertTrue("No cursor steps must fire on a simple tap", cursorSteps.isEmpty())
    }

    @Test
    fun movementBelowDeadZone_stillCommitsSpace() {
        dispatchDown(spaceX, spaceY)
        // Move 10 px right — below the 24 px dead zone.
        dispatchMove(spaceX + 10f, spaceY)
        dispatchUp(spaceX + 10f, spaceY)

        assertEquals(1, committedKeys.size)
        assertEquals(KeyCode.Space, committedKeys[0].code)
        assertTrue(cursorSteps.isEmpty())
    }

    // ── Gesture activation + step counting ───────────────────────────────────

    @Test
    fun swipeRight_beyondDeadZone_firesOnePositiveStep_andSuppressesSpaceCommit() {
        dispatchDown(spaceX, spaceY)
        // Dead zone = 24 px, step = 16 px. Cross dead zone + 1 full step.
        dispatchMove(spaceX + 24f + 16f, spaceY)
        dispatchUp(spaceX + 24f + 16f, spaceY)

        assertEquals("Space must not be committed when gesture is active", 0, committedKeys.size)
        assertEquals("One step right expected", 1, cursorSteps.sumOf { it })
    }

    @Test
    fun swipeLeft_beyondDeadZone_firesOneNegativeStep_andSuppressesSpaceCommit() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX - 24f - 16f, spaceY)
        dispatchUp(spaceX - 24f - 16f, spaceY)

        assertEquals(0, committedKeys.size)
        assertEquals(-1, cursorSteps.sumOf { it })
    }

    @Test
    fun swipeRight_twoSteps_firesCorrectCount() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 16f * 2, spaceY)
        dispatchUp(spaceX + 24f + 16f * 2, spaceY)

        assertEquals(0, committedKeys.size)
        assertEquals(2, cursorSteps.sumOf { it })
    }

    @Test
    fun swipeLeft_twoSteps_firesCorrectCount() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX - 24f - 16f * 2, spaceY)
        dispatchUp(spaceX - 24f - 16f * 2, spaceY)

        assertEquals(0, committedKeys.size)
        assertEquals(-2, cursorSteps.sumOf { it })
    }

    @Test
    fun multipleMoveCalls_accumulateDeltasCorrectly() {
        dispatchDown(spaceX, spaceY)
        // First call: cross dead zone + 1 step.
        dispatchMove(spaceX + 24f + 16f, spaceY)
        // Second call: 1 more step.
        dispatchMove(spaceX + 24f + 16f * 2, spaceY)
        dispatchUp(spaceX + 24f + 16f * 2, spaceY)

        assertEquals(0, committedKeys.size)
        // Total steps should be 2 (1 from each move).
        assertEquals(2, cursorSteps.sumOf { it })
    }

    // ── Gesture active flag ───────────────────────────────────────────────────

    @Test
    fun gestureActive_falseBeforeDeadZone() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 10f, spaceY)
        assertFalse(view.isCursorGestureActive())
    }

    @Test
    fun gestureActive_trueAfterDeadZone() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 1f, spaceY)
        assertTrue(view.isCursorGestureActive())
    }

    // ── Selection mode ────────────────────────────────────────────────────────

    @Test
    fun cursorSelectMode_false_stepsPassFalseFlag() {
        view.cursorSelectMode = false
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 16f, spaceY)
        dispatchUp(spaceX + 24f + 16f, spaceY)

        assertTrue("At least one step expected", selectFlags.isNotEmpty())
        assertTrue("All select flags must be false", selectFlags.all { !it })
    }

    @Test
    fun cursorSelectMode_true_stepsPassTrueFlag() {
        view.cursorSelectMode = true
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 16f, spaceY)
        dispatchUp(spaceX + 24f + 16f, spaceY)

        assertTrue("At least one step expected", selectFlags.isNotEmpty())
        assertTrue("All select flags must be true", selectFlags.all { it })
    }

    // ── ACTION_CANCEL resets the gesture ──────────────────────────────────────

    @Test
    fun actionCancel_resetsGesture_freshGestureWorks() {
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 16f, spaceY)  // activate gesture
        dispatchCancel()

        // After cancel, a plain tap on space must commit a space character.
        cursorSteps.clear()
        committedKeys.clear()

        dispatchDown(spaceX, spaceY)
        dispatchUp(spaceX, spaceY)

        assertEquals("Space tap after cancel must commit one space", 1, committedKeys.size)
        assertEquals(KeyCode.Space, committedKeys[0].code)
        assertTrue(cursorSteps.isEmpty())
    }

    @Test
    fun actionCancel_beforeDeadZone_spaceNotCommitted() {
        // Cancel before the gesture activates should not commit space either.
        dispatchDown(spaceX, spaceY)
        dispatchCancel()

        assertTrue("Cancel before commit must not emit a space", committedKeys.isEmpty())
    }

    // ── Non-space keys unaffected ─────────────────────────────────────────────

    @Test
    fun swipeOnCharKey_doesNotFireCursorSteps() {
        dispatchDown(charX, charY)
        // Slide far right — crosses the space key but char key owns this pointer.
        dispatchMove(charX + 200f, charY)
        dispatchUp(charX + 200f, charY)

        assertTrue("Char-key swipe must not fire cursor steps", cursorSteps.isEmpty())
    }

    @Test
    fun tapCharKey_commitsThatKey() {
        dispatchDown(charX, charY)
        dispatchUp(charX, charY)

        assertEquals(1, committedKeys.size)
        assertEquals(KeyCode.Char('a'), committedKeys[0].code)
    }

    // ── Second press after first gesture ─────────────────────────────────────

    @Test
    fun twoConsecutiveGestures_bothAccurate() {
        // First gesture: 1 step right.
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX + 24f + 16f, spaceY)
        dispatchUp(spaceX + 24f + 16f, spaceY)

        val stepsAfterFirst = cursorSteps.sumOf { it }
        cursorSteps.clear()

        // Second gesture: 2 steps left.
        dispatchDown(spaceX, spaceY)
        dispatchMove(spaceX - 24f - 16f * 2, spaceY)
        dispatchUp(spaceX - 24f - 16f * 2, spaceY)

        assertEquals(1, stepsAfterFirst)
        assertEquals(-2, cursorSteps.sumOf { it })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dispatchDown(x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_DOWN, 0, x, y))
    }

    private fun dispatchUp(x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_UP, 0, x, y))
    }

    private fun dispatchMove(x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_MOVE, 0, x, y))
    }

    private fun dispatchCancel() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun singlePointerEvent(action: Int, pointerId: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(0L, 0L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
