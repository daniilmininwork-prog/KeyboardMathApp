package dev.tally.overlay

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Stable identity for the currently-focused editable node.
 *
 * Captures the package name and window id at the moment of focus acquisition. These are
 * stable across async evaluation: if either changes the result is for a different field and
 * must be discarded (generation guard second layer).
 *
 * We intentionally do NOT hold a reference to [AccessibilityNodeInfo] here — the node is a
 * binder proxy with a bounded lifetime and must not outlive the event callback.
 */
internal data class FocusToken(
    val packageName: String,
    val windowId: Int,
    val viewIdResourceName: String?,
) {
    companion object {
        fun from(node: AccessibilityNodeInfo): FocusToken = FocusToken(
            packageName         = node.packageName?.toString() ?: "",
            windowId            = node.windowId,
            viewIdResourceName  = node.viewIdResourceName,
        )
    }
}
