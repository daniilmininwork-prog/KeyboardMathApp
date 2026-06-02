package dev.tally.keyboard.engine

/**
 * Tri-state shift/caps-lock state machine for alphabetic input.
 *
 * Transition table:
 *
 *   Current   │ Event            │ Next
 *   ──────────┼──────────────────┼────────
 *   OFF       │ shiftPressed     │ SHIFTED
 *   SHIFTED   │ shiftPressed     │ OFF        ← second tap cancels latch
 *   LOCKED    │ shiftPressed     │ OFF        ← any tap while locked releases lock
 *   SHIFTED   │ shiftDoubleTap   │ LOCKED     ← double-tap locks caps
 *   OFF/LOCKED│ shiftDoubleTap   │ (n/a — second tap from OFF is actually SHIFTED→LOCKED path)
 *   SHIFTED   │ charTyped        │ OFF        ← one-shot: reverts after a character
 *   LOCKED    │ charTyped        │ LOCKED     ← sustained: does not revert
 *   OFF       │ charTyped        │ OFF
 *
 * Double-tap detection: two [onShiftPressed] calls within [DOUBLE_TAP_WINDOW_MS] with no
 * intervening character typed are treated as a double-tap. The first tap goes OFF→SHIFTED
 * (normal); the second, if fast enough, promotes SHIFTED→LOCKED instead of reverting to OFF.
 *
 * Auto-capitalisation: [applyAutoCaps] sets the machine to SHIFTED when the field or position
 * calls for it (sentence start, all-caps, etc.) without counting as a user tap — so
 * double-tap-to-lock still requires two genuine shift presses.
 *
 * This class is pure JVM with no Android dependencies and is unit-tested in isolation.
 *
 * @param clock  Monotonic millisecond clock, defaulting to [System.currentTimeMillis]. Override
 *               in tests to control timing without real sleeps.
 */
class ShiftStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var _state: ShiftState = ShiftState.OFF

    /** Current shift state. Read-only outside this class. */
    val state: ShiftState get() = _state

    // Timestamp of the most recent shiftPressed event; -1 if none.
    private var lastShiftPressMs: Long = -1L

    // True when the pending SHIFTED state was set by auto-caps rather than a user tap.
    // Auto-caps does not participate in double-tap-to-lock.
    private var shiftedByAutoCaps: Boolean = false

    /**
     * Called when the user taps the shift key.
     *
     * Handles the full OFF→SHIFTED→LOCKED→OFF cycle and double-tap promotion.
     */
    fun onShiftPressed() {
        val now = clock()

        when (_state) {
            ShiftState.OFF -> {
                _state = ShiftState.SHIFTED
                shiftedByAutoCaps = false
                lastShiftPressMs = now
            }

            ShiftState.SHIFTED -> {
                val dt = now - lastShiftPressMs
                val isDoubleTap = !shiftedByAutoCaps &&
                    lastShiftPressMs >= 0 &&
                    dt <= DOUBLE_TAP_WINDOW_MS

                if (isDoubleTap) {
                    _state = ShiftState.LOCKED
                } else {
                    // Second tap outside the window cancels the latch.
                    _state = ShiftState.OFF
                }
                lastShiftPressMs = -1L
            }

            ShiftState.LOCKED -> {
                _state = ShiftState.OFF
                lastShiftPressMs = -1L
            }
        }
    }

    /**
     * Called after the user types a character key.
     *
     * SHIFTED reverts to OFF (one-shot); LOCKED and OFF are unaffected.
     */
    fun onCharTyped() {
        if (_state == ShiftState.SHIFTED) {
            _state = ShiftState.OFF
            lastShiftPressMs = -1L
            shiftedByAutoCaps = false
        }
    }

    /**
     * Applies auto-capitalisation requested by [capsFlags] (bit-mask from
     * [android.text.TextUtils.getCapsMode] or [EditorInfo.initialCapsMode]).
     *
     * When caps is warranted the machine moves to SHIFTED without recording a shift-press
     * timestamp, so a subsequent single shift tap correctly cancels the latch (OFF) rather
     * than locking it. A double tap after an auto-cap does still lock.
     *
     * [capsFlags] uses the same bit-mask constants as [android.text.InputType]:
     *   bit 0 → CAP_MODE_SENTENCES  (sentence start)
     *   bit 1 → CAP_MODE_WORDS      (each word)
     *   bit 2 → CAP_MODE_CHARACTERS (every character)
     *
     * If [capsFlags] is 0 and the machine is currently SHIFTED due to auto-caps, this call
     * resets to OFF so that moving the cursor away from a sentence-start de-caps appropriately.
     *
     * Calling this method does not reset a user-initiated SHIFTED or LOCKED state; the machine
     * only acts when the state is OFF or was itself set by auto-caps.
     */
    fun applyAutoCaps(capsFlags: Int) {
        val needsCaps = capsFlags != 0
        when {
            needsCaps && _state == ShiftState.OFF -> {
                _state = ShiftState.SHIFTED
                shiftedByAutoCaps = true
                // Do not update lastShiftPressMs — auto-cap is not a user tap.
            }
            !needsCaps && _state == ShiftState.SHIFTED && shiftedByAutoCaps -> {
                _state = ShiftState.OFF
                shiftedByAutoCaps = false
            }
        }
    }

    /**
     * Forces the machine to a specific state.
     *
     * Used by [KeyboardController.configure] to initialise from EditorInfo on field entry
     * when [applyAutoCaps] is not available (no cursor position yet).
     *
     * Does not affect [lastShiftPressMs]; any pending double-tap window is cancelled.
     */
    fun forceState(state: ShiftState) {
        _state = state
        lastShiftPressMs = -1L
        shiftedByAutoCaps = state == ShiftState.SHIFTED
    }

    private companion object {
        // Caps lock is the conventional 300 ms window used by most software keyboards.
        const val DOUBLE_TAP_WINDOW_MS = 300L
    }
}
