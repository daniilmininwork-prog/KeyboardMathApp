package dev.tally.ime

/**
 * A single key on the keyboard.
 *
 * @param code      The action this key triggers.
 * @param label     Text drawn on the key face; empty for Space.
 * @param widthUnits Width relative to the row's base unit (1.0 = one standard key slot).
 * @param isSpecial True for non-character keys (shift, delete, mode-switch); drawn differently.
 * @param moreKeys  Secondary characters reachable via long-press, in display order.
 *                  Empty for keys that have no alternates (most special keys, numbers,
 *                  plain symbols). When non-empty, a long-press opens the alternate tray.
 */
internal data class Key(
    val code: KeyCode,
    val label: String,
    val widthUnits: Float = 1f,
    val isSpecial: Boolean = false,
    val moreKeys: List<String> = emptyList(),
)
