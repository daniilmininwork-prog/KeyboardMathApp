package dev.tally.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OverlayDormancy.isTallyImeActive] — the AC-6 dormancy gate (TO.5).
 *
 * The input is the raw `Settings.Secure.DEFAULT_INPUT_METHOD` value (a flattened ComponentName).
 */
class OverlayDormancyTest {

    @Test
    fun `tally ime component is detected as active`() {
        assertTrue(OverlayDormancy.isTallyImeActive("dev.tally/dev.tally.ime.TallyInputMethodService"))
    }

    @Test
    fun `tally ime is detected regardless of host application id`() {
        // A different build flavour / application id must still match on the service class name.
        assertTrue(OverlayDormancy.isTallyImeActive("dev.tally.debug/dev.tally.ime.TallyInputMethodService"))
    }

    @Test
    fun `third-party keyboard is not dormancy-triggering`() {
        assertFalse(OverlayDormancy.isTallyImeActive("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"))
        assertFalse(OverlayDormancy.isTallyImeActive("com.samsung.android.honeyboard/.service.HoneyBoardService"))
    }

    @Test
    fun `null or blank default input method is not dormancy-triggering`() {
        assertFalse(OverlayDormancy.isTallyImeActive(null))
        assertFalse(OverlayDormancy.isTallyImeActive(""))
    }
}
