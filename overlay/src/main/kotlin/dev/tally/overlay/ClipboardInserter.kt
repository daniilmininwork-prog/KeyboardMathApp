package dev.tally.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts [text] into [node] via a clipboard paste, then restores the previous clipboard
 * contents after [restoreDelayMs].
 *
 * Clipboard is the most compatible insertion path for overlay mode — it works with Gboard,
 * Samsung Keyboard, and virtually every editable field on Android. Direct insertion via
 * [AccessibilityNodeInfo.ACTION_SET_TEXT] would replace the entire field, which we never want.
 *
 * The restore delay gives the paste action time to complete before the clipboard is swapped
 * back. On API 28+ the prior content is cleared if there was none; on older APIs clearing
 * is not possible without a system permission, so a single remaining entry is acceptable.
 */
internal class ClipboardInserter(
    private val clipboard: ClipboardManager,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    val restoreDelayMs: Long = RESTORE_DELAY_MS,
) {

    fun insert(node: AccessibilityNodeInfo, text: String) {
        val previousClip = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            // Paste failed — field may not be editable, the node may have been recycled,
            // or accessibility actions may be restricted. Restore the clipboard immediately
            // rather than scheduling a delayed restore, so the user's clipboard is not
            // left in an inconsistent state after a failed insertion.
            restoreClipboard(previousClip)
            return
        }
        handler.postDelayed({
            try {
                restoreClipboard(previousClip)
            } catch (e: Exception) {
                // SecurityException (Android 10+ clipboard access restrictions),
                // IllegalStateException (dead binder), or other runtime failure.
                // The paste already succeeded; the only consequence is that the user's
                // previous clipboard item is not restored. Log so this is visible in
                // crash reports and the user's clipboard loss can be diagnosed.
                Log.w(TAG, "clipboard restore failed after insert; previous clip may be lost", e)
            }
        }, restoreDelayMs)
    }

    private fun restoreClipboard(previousClip: ClipData?) {
        if (previousClip != null) {
            clipboard.setPrimaryClip(previousClip)
        } else if (Build.VERSION.SDK_INT >= 28) {
            clipboard.clearPrimaryClip()
        }
    }

    companion object {
        const val RESTORE_DELAY_MS = 500L
        private const val TAG = "ClipboardInserter"
    }
}
