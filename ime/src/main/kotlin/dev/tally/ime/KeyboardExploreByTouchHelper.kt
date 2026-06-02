package dev.tally.ime

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId

/**
 * Virtual view accessibility tree for [KeyPlaneView].
 *
 * Maps each key in the current layout to a stable virtual ID so that TalkBack and Switch
 * Access can explore and type every key. Without this, the Canvas-drawn key plane is a
 * single opaque node — blind users physically cannot type.
 *
 * Virtual IDs are derived as [KeyId.rowIndex] * [ROW_STRIDE] + [KeyId.keyIndex]. With a
 * stride of 100, this supports up to 100 keys per row, well above any real layout.
 *
 * Explore-by-touch (hover): dragging a finger across the keyboard announces each key as
 * the finger crosses its bounds. Lifting commits the focused key (lift-to-type), via
 * [onPerformActionForVirtualView] routing through the same [keyClickListener] path as a
 * real touch event.
 *
 * Nodes are invalidated on shift or layer change via [invalidateAllKeys] so TalkBack
 * reads the updated label (e.g. uppercase after Shift).
 *
 * @param host          The [KeyPlaneView] this helper is attached to.
 * @param keyClickListener  Invoked when TalkBack or Switch Access performs a click on a
 *                          virtual key node. Receives the same [Key] that a real tap would
 *                          deliver, so the dispatch path is identical.
 */
internal class KeyboardExploreByTouchHelper(
    private val host: KeyPlaneView,
    private val keyClickListener: (Key) -> Unit,
) : ExploreByTouchHelper(host) {

    /**
     * The current pixel geometry, kept in sync with [KeyPlaneView.currentGeometry].
     *
     * May be null before the first layout pass. When null, all virtual-tree operations
     * are no-ops so the helper degrades gracefully rather than crashing.
     */
    var geometry: KeyGeometry? = null

    /**
     * The current key rows, kept in sync with [KeyPlaneView.currentRows].
     *
     * Used to resolve a virtual ID back to a [Key] for ACTION_CLICK dispatch and for
     * building the content description.
     */
    var rows: List<KeyRow> = emptyList()

    // ── ExploreByTouchHelper contract ─────────────────────────────────────────

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val g = geometry ?: return INVALID_ID
        val keyId = g.keys.firstOrNull { rk -> rk.contains(x, y) }?.id ?: return INVALID_ID
        return virtualId(keyId)
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        val g = geometry ?: return
        for (rk in g.keys) {
            virtualViewIds.add(virtualId(rk.id))
        }
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat,
    ) {
        val g = geometry ?: run {
            // No geometry yet; produce a minimal valid node so the framework doesn't crash.
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 0, 0))
            return
        }

        val keyId = keyIdFromVirtualId(virtualViewId)
        val resolved = g.keyById(keyId)

        if (resolved == null) {
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 0, 0))
            return
        }

        val layoutKey = resolveKey(keyId)

        node.className = "android.widget.Button"
        node.isFocusable = true
        node.isClickable = true
        node.isEnabled = true

        // Content description: visible label + any long-press alternates.
        // TalkBack reads this aloud as the user explores.
        // Prefer the label from the live Key row (reflects shift/layer state) over the
        // static KeyDef label stored in geometry (which reflects the layout schema only).
        val label = layoutKey?.label ?: resolved.keyDef.label
        node.contentDescription = buildDescription(label, layoutKey?.moreKeys)

        // Bounds in parent coordinates (the view's own coordinate space).
        val bounds = Rect(
            resolved.left.toInt(),
            resolved.top.toInt(),
            resolved.right.toInt(),
            resolved.bottom.toInt(),
        )
        node.setBoundsInParent(bounds)

        // Map parent bounds to screen coordinates for Switch Access hit-testing.
        val screenOffset = IntArray(2)
        host.getLocationOnScreen(screenOffset)
        val screenBounds = Rect(
            bounds.left + screenOffset[0],
            bounds.top + screenOffset[1],
            bounds.right + screenOffset[0],
            bounds.bottom + screenOffset[1],
        )
        node.setBoundsInScreen(screenBounds)

        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false

        val keyId = keyIdFromVirtualId(virtualViewId)
        val key = resolveKey(keyId) ?: return false
        keyClickListener(key)
        return true
    }

    // ── Invalidation helpers ──────────────────────────────────────────────────

    /**
     * Invalidates a single key node so TalkBack re-reads its updated label.
     *
     * Call when a specific key changes (e.g., a mode-switch key label update).
     */
    fun invalidateKey(keyId: KeyId) {
        invalidateVirtualView(virtualId(keyId))
    }

    /**
     * Invalidates the entire virtual tree.
     *
     * Call after shift or layer changes that affect every key's label at once.
     */
    fun invalidateAllKeys() {
        invalidateRoot()
    }

    // ── Test seams ────────────────────────────────────────────────────────────

    /**
     * Returns the virtual ID for the key at ([x], [y]), or [INVALID_ID] if none.
     *
     * Exposes the protected [getVirtualViewAt] for unit tests running in the same
     * package without relying on reflection.
     */
    internal fun virtualViewAt(x: Float, y: Float): Int = getVirtualViewAt(x, y)

    /**
     * Populates [out] with the virtual IDs of all currently visible keys.
     *
     * Exposes the protected [getVisibleVirtualViews] for unit tests.
     */
    internal fun visibleVirtualViews(out: MutableList<Int>) = getVisibleVirtualViews(out)

    /**
     * Populates [node] with accessibility info for the given [virtualViewId].
     *
     * Exposes the protected [onPopulateNodeForVirtualView] for unit tests so
     * tests can assert on content descriptions, bounds, and actions without a
     * live accessibility session.
     */
    internal fun populateNodeForTest(virtualViewId: Int, node: AccessibilityNodeInfoCompat) =
        onPopulateNodeForVirtualView(virtualViewId, node)

    /**
     * Performs [action] on the virtual view with [virtualViewId].
     *
     * Exposes the protected [onPerformActionForVirtualView] for unit tests so
     * ACTION_CLICK can be exercised without a live TalkBack or Switch Access session.
     */
    internal fun performActionForTest(virtualViewId: Int, action: Int): Boolean =
        onPerformActionForVirtualView(virtualViewId, action, null)

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveKey(keyId: KeyId): Key? {
        val row = rows.getOrNull(keyId.rowIndex) ?: return null
        return row.keys.getOrNull(keyId.keyIndex)
    }

    private fun buildDescription(label: String, moreKeys: List<String>?): String {
        val base = when (label) {
            "⇧"  -> "Shift"
            "⌫"  -> "Backspace"
            "↵"  -> "Return"
            ""   -> "Space"
            "123" -> "Numbers"
            "ABC" -> "Letters"
            "#+=", "#+=" -> "Symbols"
            else -> label
        }
        return if (!moreKeys.isNullOrEmpty()) {
            "$base, long press for alternates: ${moreKeys.joinToString(", ")}"
        } else {
            base
        }
    }

    private companion object {
        // Stride large enough for any realistic key count per row.
        const val ROW_STRIDE = 100

        fun virtualId(keyId: KeyId): Int = keyId.rowIndex * ROW_STRIDE + keyId.keyIndex

        fun keyIdFromVirtualId(virtualId: Int): KeyId =
            KeyId(rowIndex = virtualId / ROW_STRIDE, keyIndex = virtualId % ROW_STRIDE)
    }
}
