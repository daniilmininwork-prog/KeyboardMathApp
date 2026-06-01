package dev.tally.keyboard.engine

/**
 * Resolved view of a key after geometry has been applied to a [LayoutDefinition].
 *
 * Pixel coordinates are left as a placeholder type here; [LayoutEngine] fills them
 * once a concrete viewport width/height is known. Keeping this separate from
 * [KeyDef] preserves the distinction between the data model and the rendered geometry.
 *
 * @param keyDef    Source key definition, preserved for hit-test and a11y lookup.
 * @param rowIndex  Zero-based row within the layout.
 * @param keyIndex  Zero-based position within the row.
 * @param left      Left edge as a fraction of total keyboard width.
 * @param right     Right edge as a fraction of total keyboard width.
 */
data class KeyDescriptor(
    val keyDef: KeyDef,
    val rowIndex: Int,
    val keyIndex: Int,
    val left: Float,
    val right: Float,
) {
    val width: Float get() = right - left
}
