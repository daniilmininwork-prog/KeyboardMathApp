package dev.tally.keyboard.engine

/**
 * Resolved pixel geometry for a single key within a keyboard layout.
 *
 * All coordinates are in the same pixel space as the viewport passed to
 * [LayoutEngine.layoutFor]. No Android types are referenced here — this is a plain
 * data carrier that the rendering layer uses to position its Canvas draws and that
 * [LayoutEngine.hitTest] uses for point-in-rect lookups.
 *
 * @param id    Stable identity of this key; links back to the originating [KeyDef].
 * @param keyDef The definition this geometry was derived from (label, code, moreKeys).
 * @param left   Left edge in pixels, relative to the keyboard's left edge.
 * @param top    Top edge in pixels, relative to the keyboard's top edge.
 * @param right  Right edge in pixels.
 * @param bottom Bottom edge in pixels.
 */
data class ResolvedKey(
    val id: KeyId,
    val keyDef: KeyDef,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float  get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Returns true when [x], [y] falls within the key's bounding rectangle. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * The complete pixel geometry for a keyboard viewport.
 *
 * Produced by [LayoutEngine.layoutFor] from a [LayoutDefinition] and a concrete
 * viewport size. Immutable once created; callers may cache it for the lifetime of
 * a fixed viewport (e.g., until [onConfigurationChanged]).
 *
 * @param viewportWidth  Total width of the keyboard in pixels.
 * @param viewportHeight Total height of the keyboard in pixels.
 * @param rowHeight      Height of each row in pixels (uniform across rows).
 * @param keys           All resolved keys, in row-then-key order.
 */
data class KeyGeometry(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val rowHeight: Float,
    val keys: List<ResolvedKey>,
) {
    /** Lookup map for O(1) retrieval by [KeyId]. Built lazily on first access. */
    private val byId: Map<KeyId, ResolvedKey> by lazy { keys.associateBy { it.id } }

    /** Returns the [ResolvedKey] for [id], or null if not present. */
    fun keyById(id: KeyId): ResolvedKey? = byId[id]
}
