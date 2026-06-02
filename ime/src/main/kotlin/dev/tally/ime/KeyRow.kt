package dev.tally.ime

/**
 * One horizontal row of keys.
 *
 * @param keys              The keys in order (left → right).
 * @param startOffsetUnits  Empty space at the left edge, in base units. Used to centre short rows.
 */
internal data class KeyRow(
    val keys: List<Key>,
    val startOffsetUnits: Float = 0f,
)
