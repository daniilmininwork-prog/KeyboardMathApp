package dev.tally.ime

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.BackspaceSpeed
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.LongPressDelay
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
import java.util.concurrent.TimeUnit

/**
 * Stage 2 timing-config coverage for [KeyPlaneView].
 *
 * Verifies that the two new preferences are actually consulted by the touch path, not just
 * stored:
 *   - [KeyPlaneView.longPressDelayMs] governs when the long-press tray fires (a key with
 *     alternates does not open its tray before the configured delay, and does open after it).
 *   - [KeyPlaneView.backspaceSpeed] governs the first repeat fire (no repeat before the
 *     speed's initial delay; a repeat after it).
 *
 * Geometry mirrors [BackspaceRepeatTest]: a character key on the left (which carries
 * alternates here) and a backspace key on the right, each 100×100 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InputTimingConfigTest {

    private lateinit var view: KeyPlaneView
    private var repeatCount = 0

    // Character key x=[0..100), backspace key x=[100..200), both y=[0..100).
    private val charX = 50f
    private val charY = 50f
    private val bsX = 150f
    private val bsY = 50f

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
        val bsDef = KeyDef(code = 0, label = "⌫", width = 0.5f)

        view.currentGeometry = KeyGeometry(
            viewportWidth = 200,
            viewportHeight = 100,
            rowHeight = 100f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), charDef, left = 0f, top = 0f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(0, 1), bsDef, left = 100f, top = 0f, right = 200f, bottom = 100f),
            ),
        )
        view.currentRows = listOf(
            KeyRow(listOf(
                // The 'a' key carries an alternate so a long-press tray can fire.
                Key(KeyCode.Char('a'), "a", moreKeys = listOf("@")),
                Key(KeyCode.Backspace, "⌫", widthUnits = 1f, isSpecial = true),
            )),
        )

        view.backspaceRepeatListener = { repeatCount++ }
        repeatCount = 0
    }

    private fun looper() = Shadows.shadowOf(android.os.Looper.getMainLooper())

    // ── Long-press delay ───────────────────────────────────────────────────────

    @Test
    fun longPress_doesNotFireBeforeConfiguredDelay() {
        view.longPressDelayMs = LongPressDelay.LONG.delayMs // 600 ms

        dispatchDown(pointerId = 0, x = charX, y = charY)
        // Advance to just before the configured delay.
        looper().idleFor(LongPressDelay.LONG.delayMs - 50, TimeUnit.MILLISECONDS)

        assertFalse(
            "Long-press tray must not show before the configured LONG delay elapses",
            view.isLongPressShowing(0),
        )
    }

    @Test
    fun longPress_firesAfterConfiguredDelay() {
        view.longPressDelayMs = LongPressDelay.LONG.delayMs // 600 ms

        dispatchDown(pointerId = 0, x = charX, y = charY)
        looper().idleFor(LongPressDelay.LONG.delayMs + 50, TimeUnit.MILLISECONDS)

        assertTrue(
            "Long-press tray must show once the configured LONG delay elapses",
            view.isLongPressShowing(0),
        )
    }

    @Test
    fun shortDelay_firesSoonerThanLongWouldHave() {
        view.longPressDelayMs = LongPressDelay.SHORT.delayMs // 250 ms

        dispatchDown(pointerId = 0, x = charX, y = charY)
        // A window that is past SHORT but well before LONG (600 ms): proves the field is honoured.
        looper().idleFor(LongPressDelay.SHORT.delayMs + 50, TimeUnit.MILLISECONDS)

        assertTrue(
            "With SHORT delay the tray must already be showing at ${LongPressDelay.SHORT.delayMs + 50} ms",
            view.isLongPressShowing(0),
        )
    }

    // ── Backspace speed ────────────────────────────────────────────────────────

    @Test
    fun backspaceRepeat_doesNotFireBeforeSpeedInitialDelay() {
        view.backspaceSpeed = BackspaceSpeed.SLOW // 550 ms initial delay

        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        looper().idleFor(BackspaceSpeed.SLOW.initialDelayMs - 50, TimeUnit.MILLISECONDS)

        assertEquals(
            "No repeat may fire before the SLOW speed's initial delay",
            0, repeatCount,
        )
    }

    @Test
    fun backspaceRepeat_firesAfterSpeedInitialDelay() {
        view.backspaceSpeed = BackspaceSpeed.SLOW

        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        looper().idleFor(BackspaceSpeed.SLOW.initialDelayMs + 50, TimeUnit.MILLISECONDS)

        assertTrue("A repeat must fire once the SLOW initial delay elapses", repeatCount >= 1)
    }

    @Test
    fun fastSpeed_fires_whereSlowWouldNotHave() {
        view.backspaceSpeed = BackspaceSpeed.FAST // 250 ms initial delay

        dispatchDown(pointerId = 0, x = bsX, y = bsY)
        // Window past FAST's initial delay but before SLOW's: proves the speed is applied.
        looper().idleFor(BackspaceSpeed.FAST.initialDelayMs + 50, TimeUnit.MILLISECONDS)

        assertTrue(
            "With FAST speed a repeat must fire by ${BackspaceSpeed.FAST.initialDelayMs + 50} ms",
            repeatCount >= 1,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun dispatchDown(pointerId: Int, x: Float, y: Float) {
        view.onTouchEvent(singlePointerEvent(MotionEvent.ACTION_DOWN, pointerId, x, y))
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
