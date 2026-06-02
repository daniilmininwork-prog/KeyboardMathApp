package dev.tally.keyboard.engine

/**
 * Stable identifier for a key within a laid-out keyboard.
 *
 * Equality is structural: two [KeyId]s with the same row and key index refer to the
 * same logical key position regardless of which [KeyGeometry] they came from. This
 * keeps hit-test callers free of geometry references.
 *
 * @param rowIndex  Zero-based row position from the top of the keyboard.
 * @param keyIndex  Zero-based key position within [rowIndex].
 */
data class KeyId(val rowIndex: Int, val keyIndex: Int)
