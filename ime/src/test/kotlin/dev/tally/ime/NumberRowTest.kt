package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the number-row toggle in [KeyboardController].
 *
 * Covers T1.10 acceptance criteria: the number row can be toggled, the toggle persists
 * (via the change listener), and the state is correctly initialised from preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NumberRowTest {

    private lateinit var controller: KeyboardController
    private lateinit var ic: FakeInputConnection

    @Before
    fun setUp() {
        controller = KeyboardController()
        ic = FakeInputConnection()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `number row is disabled by default`() {
        assertFalse(controller.numberRowEnabled)
    }

    @Test
    fun `setNumberRowEnabled initialises to true`() {
        controller.setNumberRowEnabled(true)
        assertTrue(controller.numberRowEnabled)
    }

    @Test
    fun `setNumberRowEnabled initialises to false`() {
        controller.setNumberRowEnabled(true)
        controller.setNumberRowEnabled(false)
        assertFalse(controller.numberRowEnabled)
    }

    // ── Toggle via key press ──────────────────────────────────────────────────

    @Test
    fun `ToggleNumberRow key flips false to true`() {
        assertFalse(controller.numberRowEnabled)
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)
        assertTrue(controller.numberRowEnabled)
    }

    @Test
    fun `ToggleNumberRow key flips true to false`() {
        controller.setNumberRowEnabled(true)
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)
        assertFalse(controller.numberRowEnabled)
    }

    @Test
    fun `ToggleNumberRow toggles back to original on second press`() {
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)
        assertFalse(controller.numberRowEnabled)
    }

    // ── Change listener ───────────────────────────────────────────────────────

    @Test
    fun `listener fires with true when toggled on`() {
        var received: Boolean? = null
        controller.numberRowChangeListener = { enabled -> received = enabled }

        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)

        assertTrue("listener must receive true", received == true)
    }

    @Test
    fun `listener fires with false when toggled off`() {
        controller.setNumberRowEnabled(true)
        var received: Boolean? = null
        controller.numberRowChangeListener = { enabled -> received = enabled }

        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)

        assertTrue("listener must receive false", received == false)
    }

    @Test
    fun `listener not called when toggle key is absent`() {
        var called = false
        controller.numberRowChangeListener = { called = true }

        controller.handleKey(Key(KeyCode.Char('a'), "a"), ic)

        assertFalse("listener must not fire for character keys", called)
    }

    @Test
    fun `no listener fires when no listener is wired`() {
        // Must not throw when no listener is set.
        controller.numberRowChangeListener = null
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)
        assertTrue(controller.numberRowEnabled)
    }

    // ── Layer isolation ───────────────────────────────────────────────────────

    @Test
    fun `toggle does not affect keyboard state layer`() {
        controller.configure(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, restarting = false)
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)

        // Toggling the number row must not change the ALPHA_LOWER state.
        assertTrue(controller.currentState() == KeyboardState.ALPHA_LOWER)
    }

    @Test
    fun `toggle does not affect shift state`() {
        controller.handleKey(Key(KeyCode.Shift, "⇧", isSpecial = true), ic)
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)

        // Shift was pressed once → ALPHA_UPPER; number row toggle must leave it upper.
        assertTrue(controller.currentState() == KeyboardState.ALPHA_UPPER)
    }

    @Test
    fun `toggle does not issue any InputConnection calls`() {
        ic.calls.clear()
        controller.handleKey(Key(KeyCode.ToggleNumberRow, "#", isSpecial = true), ic)

        assertTrue("ToggleNumberRow must not touch the InputConnection", ic.calls.isEmpty())
    }
}
