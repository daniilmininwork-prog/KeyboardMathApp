package dev.tally.keyboard.engine

/**
 * Tri-state shift model for an alphabetic keyboard layer.
 *
 * The three states map to distinct user-visible behaviours:
 *
 *   OFF     — lower-case; the shift key is unpressed.
 *   SHIFTED — one-shot upper-case; reverts to OFF after the next character key.
 *             Visually: shift key is highlighted ("latched").
 *   LOCKED  — sustained upper-case (caps lock); does not revert on a character key.
 *             Visually: shift key shows a different indicator (e.g. filled, double underline).
 *
 * Only [ShiftStateMachine] should mutate shift state; callers read [ShiftState] to decide
 * which character variant to emit and which visual to render.
 */
enum class ShiftState {
    OFF,
    SHIFTED,
    LOCKED,
}
