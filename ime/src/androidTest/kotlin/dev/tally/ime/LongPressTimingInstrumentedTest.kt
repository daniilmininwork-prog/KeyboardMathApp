package dev.tally.ime

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.LongPressDelay
import dev.tally.keyboard.engine.ResolvedKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device timing verification for the touch-and-hold (long-press) delay preference.
 *
 * The emulator harness cannot demonstrate the sub-second [LongPressDelay] thresholds through
 * `adb input` / `screencap`: per-call input-injection overhead (hundreds of ms) swamps any
 * sub-second dwell, and the screencap framebuffer-grab moment drifts by hundreds of ms between
 * sessions — both coarser and noisier than the 350 ms gap between SHORT (250 ms) and LONG
 * (600 ms). Matched-duration A/B trials therefore reverse run-to-run.
 *
 * This test removes that noise by driving the REAL [KeyPlaneView] on the device's REAL main
 * [android.os.Looper] and arming the long-press timer with the real [android.os.Handler]. Time
 * is advanced with real wall-clock [SystemClock.sleep], and the main queue is pumped with
 * [androidx.test.platform.app.Instrumentation.waitForIdleSync] so the posted tray Runnable runs
 * exactly when its delay elapses.
 *
 * The threshold is read through [KeyPlaneView.isLongPressActive] — true the instant the timer
 * fires and the tray is triggered, which is exactly the state the touch-and-hold delay governs.
 * (We deliberately do not assert on the [android.widget.PopupWindow]'s `isShowing`: window-token
 * mapping is unrelated to the delay and is itself flaky to observe on the emulator.) The view is
 * still hosted in a real Activity window so the production tray code path runs unchanged.
 *
 * Acceptance (mirrors InputTimingConfigTest, but on a real device clock):
 *   - With LONG, the tray is not showing just before 600 ms and is showing just after.
 *   - At a time past SHORT but before LONG, SHORT has fired the tray while LONG has not — the
 *     crisp matched-duration distinction the manual emulator check could not reproduce.
 */
@RunWith(AndroidJUnit4::class)
class LongPressTimingInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<KeyPlaneTestActivity>
    private lateinit var view: KeyPlaneView

    // A single character key spanning the whole 200x100 host, carrying one alternate so a tray
    // can fire. pointerId 0 presses its centre.
    private val px = 100f
    private val py = 50f
    private val pointerId = 0

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(KeyPlaneTestActivity::class.java)
        scenario.onActivity { activity ->
            view = KeyPlaneView(activity)
            activity.host.addView(view, ViewGroup.LayoutParams(200, 100))
            // Rows first: the currentRows setter calls requestLayout(), and onLayout rebuilds
            // currentGeometry from the rows against the view's measured bounds. We re-inject the
            // fixed test geometry right before each DOWN (see armDown) so the press always lands
            // on a known key regardless of how the view measured itself.
            view.currentRows = listOf(
                KeyRow(listOf(Key(KeyCode.Char('a'), "a", moreKeys = listOf("@")))),
            )
        }
        // Let the window finish attaching so the long-press Handler runs against a live Looper.
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        // Lift the finger and dismiss any tray so popups never leak across tests.
        scenario.onActivity { dispatch(MotionEvent.ACTION_CANCEL) }
        instrumentation.waitForIdleSync()
        scenario.close()
    }

    @Test
    fun longDelay_trayHiddenJustBeforeThreshold() {
        configure(LongPressDelay.LONG) // 600 ms
        pressDown()

        // 50 ms shy of the threshold the tray must still be hidden.
        advance(LongPressDelay.LONG.delayMs - 50)
        assertFalse(
            "LONG: tray must stay hidden before the 600 ms delay elapses",
            showing(),
        )
    }

    @Test
    fun longDelay_trayShownJustAfterThreshold() {
        configure(LongPressDelay.LONG)
        pressDown()

        advance(LongPressDelay.LONG.delayMs + 80)
        assertTrue(
            "LONG: tray must appear once the 600 ms delay elapses",
            showing(),
        )
    }

    @Test
    fun shortFires_whereLongWouldNotHave_atMatchedHoldTime() {
        // The matched-duration distinction the manual emulator check could not pin down: at the
        // SAME elapsed hold time (past SHORT's 250 ms, well before LONG's 600 ms), SHORT shows
        // the tray and LONG does not. Run both on the same real clock, back to back.
        val matched = (LongPressDelay.SHORT.delayMs + LongPressDelay.LONG.delayMs) / 2 // 425 ms

        configure(LongPressDelay.SHORT)
        pressDown()
        advance(matched)
        val shortShowing = showing()
        scenario.onActivity { dispatch(MotionEvent.ACTION_UP) }
        instrumentation.waitForIdleSync()

        configure(LongPressDelay.LONG)
        pressDown()
        advance(matched)
        val longShowing = showing()
        scenario.onActivity { dispatch(MotionEvent.ACTION_UP) }
        instrumentation.waitForIdleSync()

        assertTrue("At $matched ms SHORT must have opened the tray", shortShowing)
        assertFalse("At $matched ms LONG must NOT have opened the tray", longShowing)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun configure(delay: LongPressDelay) {
        scenario.onActivity { view.longPressDelayMs = delay.delayMs }
    }

    private fun pressDown() {
        // Re-inject the fixed geometry then press, atomically on the main thread, so a stray
        // layout pass cannot replace the geometry between injection and the DOWN hit-test.
        scenario.onActivity {
            view.currentGeometry = KeyGeometry(
                viewportWidth = 200,
                viewportHeight = 100,
                rowHeight = 100f,
                keys = listOf(
                    ResolvedKey(
                        KeyId(0, 0),
                        KeyDef(code = 'a'.code, label = "a", width = 1f),
                        left = 0f, top = 0f, right = 200f, bottom = 100f,
                    ),
                ),
            )
            dispatch(MotionEvent.ACTION_DOWN)
        }
    }

    private fun showing(): Boolean {
        // Read the seam on the main thread; long-press state is main-thread-confined.
        var result = false
        scenario.onActivity { result = view.isLongPressActive(pointerId) }
        return result
    }

    /**
     * Advances real wall-clock time by [ms], pumping the main Looper in small steps so the
     * long-press Runnable (posted via Handler.postDelayed against the real clock) executes as
     * soon as its delay elapses. The DOWN that armed the timer ran on the main thread, so we
     * sleep off-thread (keeping the Looper free) and force a sync after each step; a due delayed
     * message is dispatched before the Looper reports idle, so the state is observable here.
     */
    private fun advance(ms: Long) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(STEP_MS)
            instrumentation.waitForIdleSync()
        }
    }

    private companion object {
        // Granularity of the time-advance pump. Small enough that the observed firing time is
        // close to the configured threshold, large enough to keep the test fast.
        const val STEP_MS = 25L
    }

    private fun dispatch(action: Int) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f; size = 1f
        })
        val ev = MotionEvent.obtain(
            now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.onTouchEvent(ev)
        ev.recycle()
    }
}

/**
 * Bare host Activity for [LongPressTimingInstrumentedTest]; provides a real window so the
 * long-press [android.widget.PopupWindow] can attach and report visibility. Test-only.
 */
class KeyPlaneTestActivity : Activity() {
    lateinit var host: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
