package dev.tally.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [KeyboardController.moveCursor] (T4.4).
 *
 * Acceptance criteria:
 *   - Negative steps send DPAD_LEFT key events.
 *   - Positive steps send DPAD_RIGHT key events.
 *   - [|steps|] pairs of DOWN+UP events are sent for each call.
 *   - select=false → no SHIFT meta-state on the events.
 *   - select=true  → SHIFT meta-state present on every event.
 *   - steps=0 → no key events sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CursorMovementTest {

    private lateinit var controller: KeyboardController
    private lateinit var fakeIc: FakeInputConnection

    @Before
    fun setUp() {
        controller = KeyboardController()
        fakeIc = FakeInputConnection()
        controller.configure(EditorInfo(), restarting = false)
    }

    // ── Basic direction ───────────────────────────────────────────────────────

    @Test
    fun oneStepLeft_sendsDpadLeft() {
        controller.moveCursor(steps = -1, select = false, ic = fakeIc)

        val events = fakeIc.sentKeyEvents
        assertEquals("Expected 2 key events (DOWN+UP)", 2, events.size)
        assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT })
    }

    @Test
    fun oneStepRight_sendsDpadRight() {
        controller.moveCursor(steps = 1, select = false, ic = fakeIc)

        val events = fakeIc.sentKeyEvents
        assertEquals(2, events.size)
        assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT })
    }

    // ── Step count ────────────────────────────────────────────────────────────

    @Test
    fun twoStepsRight_sendsFourKeyEvents() {
        controller.moveCursor(steps = 2, select = false, ic = fakeIc)

        // 2 steps × 2 events (DOWN+UP) = 4 events.
        assertEquals(4, fakeIc.sentKeyEvents.size)
    }

    @Test
    fun threeStepsLeft_sendsSixKeyEvents() {
        controller.moveCursor(steps = -3, select = false, ic = fakeIc)

        assertEquals(6, fakeIc.sentKeyEvents.size)
        assertTrue(fakeIc.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT })
    }

    // ── Meta-state for selection ──────────────────────────────────────────────

    @Test
    fun selectFalse_noShiftMeta() {
        controller.moveCursor(steps = 1, select = false, ic = fakeIc)

        fakeIc.sentKeyEvents.forEach { event ->
            assertEquals("No shift meta expected", 0,
                event.metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        }
    }

    @Test
    fun selectTrue_shiftMetaPresent() {
        controller.moveCursor(steps = 1, select = true, ic = fakeIc)

        fakeIc.sentKeyEvents.forEach { event ->
            assertTrue("SHIFT meta must be set for selection",
                event.metaState and KeyEvent.META_SHIFT_ON != 0)
        }
    }

    @Test
    fun selectTrue_leftDirection_shiftPlusDpadLeft() {
        controller.moveCursor(steps = -2, select = true, ic = fakeIc)

        assertEquals(4, fakeIc.sentKeyEvents.size)
        fakeIc.sentKeyEvents.forEach { event ->
            assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, event.keyCode)
            assertTrue(event.metaState and KeyEvent.META_SHIFT_ON != 0)
        }
    }

    // ── Zero steps ────────────────────────────────────────────────────────────

    @Test
    fun zeroSteps_noKeyEventsSent() {
        controller.moveCursor(steps = 0, select = false, ic = fakeIc)

        assertTrue("Zero steps must not send any key events", fakeIc.sentKeyEvents.isEmpty())
    }

    // ── DOWN/UP pairing ───────────────────────────────────────────────────────

    @Test
    fun events_alternateDownAndUp() {
        controller.moveCursor(steps = 2, select = false, ic = fakeIc)

        val events = fakeIc.sentKeyEvents
        assertEquals(4, events.size)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(KeyEvent.ACTION_UP,   events[1].action)
        assertEquals(KeyEvent.ACTION_DOWN, events[2].action)
        assertEquals(KeyEvent.ACTION_UP,   events[3].action)
    }

    // ── FakeInputConnection ───────────────────────────────────────────────────

    /**
     * Minimal fake [android.view.inputmethod.InputConnection] that records [KeyEvent]s
     * sent via [sendKeyEvent].
     *
     * All other operations return no-op defaults so [KeyboardController] can call
     * [finishComposingText] without crashing.
     */
    private inner class FakeInputConnection : android.view.inputmethod.InputConnectionWrapper(null, true) {

        val sentKeyEvents = mutableListOf<KeyEvent>()

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            sentKeyEvents += event
            return true
        }

        override fun finishComposingText(): Boolean = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
    }
}
