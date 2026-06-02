package dev.tally.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import dev.tally.design.MathResultChip

/**
 * Manages the draw-over-apps chip window shown by [TallyOverlayService].
 *
 * The window uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], which is the
 * correct window type for an accessibility service and requires no additional runtime
 * permissions beyond [android.permission.BIND_ACCESSIBILITY_SERVICE].
 *
 * Tap-jacking is mitigated by setting [android.view.View.setFilterTouchesWhenObscured] on
 * the chip view: Android will silently drop touch events when this window is overlaid by
 * another window, preventing a malicious app from tricking the user into tapping the chip.
 *
 * The window has [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] so it never steals focus
 * from the field the user is typing in.
 */
internal class OverlayChipWindow(private val context: Context) {

    /** Called when the user taps the chip to insert the result. */
    var onInsert: (() -> Unit)? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val chip = MathResultChip(context).also { chip ->
        chip.filterTouchesWhenObscured = true
        chip.onTap = { onInsert?.invoke() }
    }
    private val container = FrameLayout(context).also { frame ->
        frame.addView(
            chip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    private var isAttached = false

    /**
     * Shows the chip with [text] positioned just above [fieldBounds].
     *
     * Safe to call when the chip is already visible — updates text and repositions.
     */
    fun show(text: String, fieldBounds: Rect) {
        val params = buildWindowParams(fieldBounds)
        chip.show(text)
        if (isAttached) {
            try {
                windowManager.updateViewLayout(container, params)
            } catch (e: IllegalArgumentException) {
                // View was removed externally (e.g. service was briefly torn down and recreated).
                // isAttached is now stale — correct it and fall through to addView on the next
                // show() call.
                Log.e(TAG, "show: updateViewLayout failed (view not attached); resetting and retrying addView", e)
                isAttached = false
                // Re-attempt addView immediately so this show() call is not silently dropped.
                addViewSafe(params)
            }
        } else {
            addViewSafe(params)
        }
    }

    /**
     * Calls [WindowManager.addView] and tracks the result in [isAttached].
     *
     * Uses [IllegalArgumentException] rather than message-string heuristics to detect the
     * view-already-added case. The correct pattern is: always try [updateViewLayout] first
     * (done in [show]) and only call [addView] when the view is not yet attached.
     */
    private fun addViewSafe(params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(container, params)
            isAttached = true
        } catch (e: IllegalStateException) {
            // Non-duplicate-add cause (e.g. invalid/expired window token, incorrect LayoutParams
            // type, or accessibility overlay permission revoked mid-session). Log so the failure
            // is visible in crash reports and reset isAttached so future show() calls retry.
            Log.e(TAG, "addViewSafe: WindowManager.addView threw IllegalStateException; chip will not be shown", e)
            isAttached = false
        } catch (e: IllegalArgumentException) {
            // WindowManager.addView also throws IllegalArgumentException (via BadTokenException,
            // a subclass of IAE) when the window token is invalid or has been revoked
            // (e.g. accessibility overlay token expired mid-session, permission revoked).
            // The already-added case is guarded by isAttached checks in show(), so this catch
            // handles the bad-token scenario only.
            Log.e(TAG, "addViewSafe: WindowManager.addView threw IllegalArgumentException (bad/expired token); chip will not be shown", e)
            isAttached = false
        }
    }

    /**
     * Dismisses and removes the chip window. Safe to call when not showing.
     */
    fun dismiss() {
        chip.dismiss()
        if (isAttached) {
            try {
                windowManager.removeView(container)
            } catch (e: IllegalArgumentException) {
                // IllegalArgumentException means the view was already removed externally,
                // was never added by this WindowManager instance, or the binder call failed.
                // All cases are benign from a user perspective — the chip is gone regardless.
                // Log at warn so a developer debugging a chip that refused to disappear can
                // find this site; state is reset below so future dismiss() calls do not loop.
                Log.w(TAG, "removeView failed — view may have been removed externally", e)
            }
            isAttached = false
        }
    }

    /** Dismisses and releases resources. Call from [TallyOverlayService.onDestroy]. */
    fun destroy() = dismiss()

    private fun buildWindowParams(fieldBounds: Rect): WindowManager.LayoutParams {
        val chipH = dpToPx(CHIP_HEIGHT_DP).toInt()
        val margin = dpToPx(CHIP_MARGIN_DP).toInt()
        // Position chip so its bottom edge sits CHIP_MARGIN_DP above the field's top.
        val y = (fieldBounds.top - chipH - margin).coerceAtLeast(margin)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.y = y
        }
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)

    private companion object {
        const val CHIP_HEIGHT_DP = 44f
        const val CHIP_MARGIN_DP = 8f
        const val TAG = "OverlayChipWindow"
    }
}
