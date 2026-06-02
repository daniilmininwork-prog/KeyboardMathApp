package dev.tally.ime

/**
 * Coarse keyboard mode: which key plane is currently visible.
 *
 * Shift sub-states (off/shifted/locked) are managed by [ShiftStateMachine] in keyboard-engine
 * and are separate from this enum. The ALPHA_* variants here reflect the visible layer:
 *
 *   ALPHA_LOWER  — lower-case alpha; shift state is OFF.
 *   ALPHA_UPPER  — upper-case alpha; shift state is SHIFTED (one-shot) or LOCKED (caps lock).
 *
 * Because both SHIFTED and LOCKED render the upper-case layer, the distinction for rendering
 * is read from [ShiftStateMachine.state] directly by the view; [KeyboardState] only decides
 * *which key grid to show*.
 */
internal enum class KeyboardState {
    ALPHA_LOWER,
    ALPHA_UPPER,
    NUMERIC,
    SYMBOLS,
}
