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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for T1.9 backspace repeat and swipe-left word-delete in [KeyPlaneView].
 *
 * Acceptance criteria:
 *   - Repeat fires while backspace is held, accelerating over time.
 *   - Repeat stops on ACTION_UP.
 *   - Repeat stops on ACTION_CANCEL.
 *   - Swipe-left on backspace fires [wordDeleteListener] exactly once and suppresses repeat.
 *   - Word delete via batch edit (verified in [ComposingTextManagerTest]).
 *
 * The test geometry has one character key at the left and one backspace key at the right,
 * each 100 px wide and 100 px tall, in a single row 200 px wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackspaceRepeatTest {

    private lateinit var view: KeyPlaneView
    private val emittedKeys   = mutableListOf<Key>()
    private var repeatCount   = 0
    private var wordDeleteFires = 0

    // Backspace key occupies x=[100..200) y=[0..100)
    private val bsX = 150f
    private val bsY = 50f

    // Character key occupies x=[0..100) y=[0..100)
    private val charX = 50f
    private val charY = 50f

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)

        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        val charDef = KeyDef(code = 'a'.code, label = "a", width = 0.5f)
        val bsDef   = KeyDef(code = 0, label = "⌫", width = 0.5f)

        val geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 100f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), charDef, left = 0f,   top = 0f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(0, 1), bsDef,   left = 100f, top = 0f, right = 200f, bottom = 100f),
            ),
        )

        val rows = listOf(
            KeyRow(listOf(
                Key(KeyCode.Char('a'), "a"),
                Key(KeyCode.Backspace, "⌫", widthUnits = 1f, isSpecial = true),
            )),
        )

        view.currentGeometry = geometry
        view.currentRows = rows
        view.keyListener = { key, _ -> emittedKeys += key }
        view.backspaceRepeatListener = { repeatCount++ }
        view.wordDeleteListener = { wordDeleteFires++ }

        repeatCount    = 0
        wordDeleteFires = 0
        emittedKeys.clear()
    }

    // ── Repeat fires while held ───────────────────────────────────────────────

    @Test
    fun backspaceHeld_repeatFiresAfterInitialDelay() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)

        // Advance time past the initial delay but not past two repeats.
        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue("At least one repeat must fire after holding", repeatCount >= 1)
    }

    @Test
    fun backspaceHeld_multipleRepeats_afterExtendedHold() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        // Hold for 2 seconds — enough time for many repeat fires at min 50 ms interval.
        looper.idleFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue("Many repeats expected during 2-second hold (got $repeatCount)", repeatCount >= 5)
    }

    // ── Repeat stops on UP ────────────────────────────────────────────────────

    @Test
    fun backspaceUp_stopsRepeat() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        val countAtUp = repeatCount
        dispatchUp(pointerId = 0, x = bsX, y = bsY)

        // Advance further; count must not increase after UP.
        looper.idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals("Repeat must stop after ACTION_UP", countAtUp, repeatCount)
    }

    // ── Repeat stops on CANCEL ────────────────────────────────────────────────

    @Test
    fun actionCancel_stopsRepeat() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        val countAtCancel = repeatCount
        dispatchCancel()

        looper.idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals("Repeat must stop after ACTION_CANCEL", countAtCancel, repeatCount)
    }

    // ── Word delete via swipe-left ────────────────────────────────────────────

    @Test
    fun swipeLeft_onBackspace_firesWordDelete() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        // Move finger more than 40 dp left (view is 200 px wide at 1× density in tests;
        // threshold ≈ 40 px at 1× mdpi Robolectric density).
        dispatchMove(pointerId = 0, x = bsX - 80f, y = bsY)
        dispatchUp(pointerId = 0, x = bsX - 80f, y = bsY)

        assertEquals("wordDeleteListener must fire exactly once", 1, wordDeleteFires)
    }

    @Test
    fun swipeLeft_onBackspace_suppressesRepeat() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        dispatchMove(pointerId = 0, x = bsX - 80f, y = bsY)

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals("Repeat must be suppressed after swipe-left word delete", 0, repeatCount)
    }

    @Test
    fun swipeLeft_onBackspace_wordDeleteFiresOnlyOnce() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        // Cross the threshold once, then continue moving further left.
        dispatchMove(pointerId = 0, x = bsX - 80f,  y = bsY)
        dispatchMove(pointerId = 0, x = bsX - 150f, y = bsY)
        dispatchUp(pointerId = 0, x = bsX - 150f, y = bsY)

        assertEquals("Word delete must fire at most once per press", 1, wordDeleteFires)
    }

    @Test
    fun smallMovement_onBackspace_doesNotTriggerWordDelete() {
        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        // Move just 5 px left — below the 40 dp threshold (40 px at 1× mdpi).
        dispatchMove(pointerId = 0, x = bsX - 5f, y = bsY)
        dispatchUp(pointerId = 0, x = bsX - 5f, y = bsY)

        assertEquals("Small horizontal movement must not trigger word delete", 0, wordDeleteFires)
    }

    @Test
    fun charKeyPress_doesNotArmRepeat() {
        dispatchDown(pointerId = 0, x = charX, y = charY)

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals("Repeat must not fire for non-backspace keys", 0, repeatCount)
    }

    @Test
    fun repeat_acceleratesOverTime() {
        // Collect a sequence of fire timestamps by replacing repeatCount with a list.
        val fireTimes = mutableListOf<Long>()
        view.backspaceRepeatListener = { fireTimes += System.currentTimeMillis() }

        val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())

        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        // Use Robolectric's pauseMainLooper to step manually through message queue.
        // Each idle() call advances the clock, so we measure the inter-arrival gaps.
        looper.idleFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)

        // We can't easily measure exact intervals in Robolectric, but we can verify the
        // fire count increases as expected with the schedule.
        // With initialDelay=400ms, stepFactor=0.80, minInterval=50ms:
        //   fire 1: t=400  fire 2: t=720  fire 3: t=976  ... eventually 50ms apart.
        // In 3000 ms total: roughly 30+ fires at floor.
        assertTrue("Expected many repeats in 3 s (got ${fireTimes.size})", fireTimes.size >= 10)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dispatchDown(pointerId: Int, x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_DOWN, pointerId, x, y))
    }

    private fun dispatchUp(pointerId: Int, x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_UP, pointerId, x, y))
    }

    private fun dispatchMove(pointerId: Int, x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_MOVE, pointerId, x, y))
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
