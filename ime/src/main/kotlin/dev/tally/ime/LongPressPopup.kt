package dev.tally.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import dev.tally.keyboard.engine.ResolvedKey

/**
 * Mini-keyboard popup shown when the user long-presses a key that has alternate characters.
 *
 * Renders a single horizontal row of alternate glyphs (moreKeys) in individual cells.
 * The caller drives selection with [onMove]: as the finger slides, the cell under the
 * pointer is highlighted. When [selectedIndex] is read on UP, it returns the chosen
 * alternate (or -1 if the finger never entered a cell — the caller should commit the
 * primary key in that case).
 *
 * The popup anchors itself so the first cell is horizontally centred over the long-pressed
 * key. If that placement would extend off the left edge of the keyboard, the popup is
 * shifted right; off the right edge, it is shifted left. Vertical placement is always
 * above the key's top edge by [R.dimen.long_press_popup_vertical_offset].
 *
 * Lifecycle (mirrors [KeyPreviewPopup]):
 *   1. Caller calls [show] on the long-press timeout.
 *   2. Caller routes every subsequent MOVE event to [onMove].
 *   3. Caller reads [selectedIndex] on UP (or [isShowing] to distinguish cancel).
 *   4. Caller calls [dismiss] on UP / CANCEL.
 */
@SuppressLint("InflateParams")
internal class LongPressPopup(private val context: Context) {

    private val density = context.resources.displayMetrics.density

    private val cellSize: Float = density * CELL_SIZE_DP
    private val textSize: Float = density * TEXT_SIZE_DP
    private val padding: Float  = density * PADDING_DP

    private val labels = mutableListOf<String>()

    // The index of the currently highlighted cell, or -1 for none.
    var selectedIndex: Int = -1
        private set

    private val contentView: PopupContentView = PopupContentView(context)

    private val popup = PopupWindow(contentView, 0, 0, false).apply {
        setBackgroundDrawable(ColorDrawable(0))
        isOutsideTouchable = false
        isTouchable       = false
        elevation         = density * ELEVATION_DP
    }

    val isShowing: Boolean get() = popup.isShowing

    /**
     * Displays the popup above [anchoredKey] relative to [anchor].
     *
     * [moreKeys] must be non-empty; callers must check before calling.
     * The first cell is treated as the default (leftmost in the tray).
     *
     * @param anchoredKey The key that was long-pressed; provides the position for placement.
     * @param anchor      The [KeyPlaneView] the popup is attached to (supplies a window token).
     * @param moreKeys    Alternate characters to display, in order.
     */
    fun show(anchoredKey: ResolvedKey, anchor: View, moreKeys: List<String>) {
        labels.clear()
        labels.addAll(moreKeys)
        selectedIndex = -1

        val count = labels.size
        val popupWidth  = (count * cellSize + padding * 2).toInt()
        val popupHeight = (cellSize + padding * 2).toInt()

        contentView.labels = labels.toList()
        contentView.selected = -1
        contentView.cellSize = cellSize
        contentView.textSize = textSize
        contentView.padding = padding

        val verticalOffset = context.resources.getDimensionPixelSize(
            R.dimen.long_press_popup_vertical_offset,
        )

        // Centre over the triggering key's horizontal midpoint.
        val idealLeft = (anchoredKey.centerX - popupWidth / 2f).toInt()
        val clampedLeft = idealLeft.coerceIn(0, (anchor.width - popupWidth).coerceAtLeast(0))

        val yOff = (anchoredKey.top.toInt() - popupHeight - verticalOffset)

        popup.width  = popupWidth
        popup.height = popupHeight

        if (popup.isShowing) {
            popup.update(anchor, clampedLeft, yOff, popupWidth, popupHeight)
        } else {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, clampedLeft, yOff)
        }

        // Remember the left edge in view coordinates so onMove can map x → cell index.
        contentView.popupLeftInAnchor = clampedLeft.toFloat()
    }

    /**
     * Updates the highlighted cell based on the pointer's position in anchor coordinates.
     *
     * The x coordinate is mapped to a cell index. The y coordinate is not used — the popup
     * is a single row and any y value that keeps the finger reasonably near the popup plane
     * is accepted. Returns the newly highlighted index (or -1).
     */
    fun onMove(x: Float): Int {
        if (!popup.isShowing || labels.isEmpty()) return -1

        val relativeX = x - contentView.popupLeftInAnchor - padding
        val index = (relativeX / cellSize).toInt()
        val clamped = if (index < 0 || index >= labels.size) -1 else index

        if (clamped != selectedIndex) {
            selectedIndex = clamped
            contentView.selected = clamped
            contentView.invalidate()
        }
        return selectedIndex
    }

    /** Hides the popup immediately. Safe to call when already dismissed. */
    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
        selectedIndex = -1
    }

    // ── Inner rendering view ──────────────────────────────────────────────────

    /**
     * Custom view that draws the alternate-key cells inside the popup.
     *
     * Kept as an inner class so it shares the pre-allocated [Paint] with the outer popup.
     * No allocations happen in [onDraw].
     */
    private class PopupContentView(context: Context) : View(context) {

        var labels: List<String> = emptyList()
        var selected: Int = -1
        var cellSize: Float = 0f
        var textSize: Float = 0f
        var padding: Float  = 0f

        /** Left edge of this popup in the anchor view's coordinate space. */
        var popupLeftInAnchor: Float = 0f

        // Pre-allocated; colors are refreshed from theme tokens at the start of each onDraw.
        private val bgPaint       = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint     = Paint(Paint.ANTI_ALIAS_FLAG)

        private val cornerRadius: Float =
            context.resources.getDimension(R.dimen.long_press_popup_corner_radius)

        // Token provider — resolves colors from the current Context configuration on each access.
        private val theme = dev.tally.design.KeyTheme(context)

        init {
            // Ensure setLayerType is set so the background drawable alpha works cleanly.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            if (labels.isEmpty()) return

            // Refresh colors from theme tokens so light↔dark switches take effect immediately.
            bgPaint.color       = theme.longPressPopupBg
            selectedPaint.color = theme.longPressPopupSelectedBg
            textPaint.color     = theme.keyText

            // Background
            canvas.drawRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                cornerRadius, cornerRadius, bgPaint,
            )

            textPaint.textSize = textSize
            textPaint.textAlign = Paint.Align.CENTER

            labels.forEachIndexed { i, label ->
                val cellLeft  = padding + i * cellSize
                val cellRight = cellLeft + cellSize
                val cellTop   = padding
                val cellBot   = cellTop + cellSize

                if (i == selected) {
                    canvas.drawRoundRect(
                        cellLeft, cellTop, cellRight, cellBot,
                        cornerRadius, cornerRadius, selectedPaint,
                    )
                }

                val textY = cellTop + cellSize / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(label, cellLeft + cellSize / 2f, textY, textPaint)
            }
        }
    }

    private companion object {
        const val CELL_SIZE_DP  = 44f
        const val TEXT_SIZE_DP  = 20f
        const val PADDING_DP    = 6f
        const val ELEVATION_DP  = 6f
    }
}
