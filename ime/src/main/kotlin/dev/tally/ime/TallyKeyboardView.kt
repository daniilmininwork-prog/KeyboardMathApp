package dev.tally.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Custom keyboard view that draws all keys onto a Canvas.
 *
 * Layout model: the row width is divided into 10 equal base units. Each key declares its width
 * in those units ([Key.widthUnits]). Rows that don't fill 10 units use [KeyRow.startOffsetUnits]
 * to centre themselves (e.g. the 9-key ASDFGHJKL row starts at 0.5 units).
 *
 * Supports dark/light theming via color resources in [R.color], RTL via LayoutDirection, and
 * large-font-scale via sp→px conversion for key text.
 */
internal class TallyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface KeyListener {
        fun onKeyPress(key: Key)
    }

    var keyListener: KeyListener? = null

    // ── Paints ────────────────────────────────────────────────────────────────

    // Pre-allocated to avoid in-draw allocation. Colors are refreshed from theme tokens at the
    // start of each onDraw so light↔dark switches take effect on the next frame.
    private val bgPaint          = Paint()
    private val keyPaint         = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialKeyPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedKeyPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint        = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    // Token provider — resolves colors from the current Context configuration on each access.
    internal val theme = dev.tally.design.KeyTheme(context)

    // ── Dimensions ────────────────────────────────────────────────────────────

    private val rowHeightPx = dpToPx(54f).toInt()
    private val cornerRadius = dpToPx(8f)
    private val hGapHalf = dpToPx(3f)   // half of the 6dp horizontal gap between keys
    private val vGapHalf = dpToPx(4f)   // half of the 8dp vertical gap
    private val keyTextSizePx = spToPx(18f)
    private val specialTextSizePx = spToPx(14f)

    // ── State ─────────────────────────────────────────────────────────────────

    private var rows: List<KeyRow> = emptyList()
    private var keyRects: List<List<Pair<Key, RectF>>> = emptyList()
    private var pressedKey: Key? = null

    // Long-press backspace repeat
    private val handler = Handler(Looper.getMainLooper())
    private val repeatInterval = ViewConfiguration.getKeyRepeatDelay().toLong()
    private var repeatRunnable: Runnable? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun setRows(newRows: List<KeyRow>) {
        rows = newRows
        requestLayout()
    }

    // ── Measure & layout ──────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = rowHeightPx * rows.size
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeRects(w, h)
    }

    private fun recomputeRects(w: Int, h: Int) {
        if (rows.isEmpty()) { keyRects = emptyList(); return }

        val rowH = h.toFloat() / rows.size
        val unit = w.toFloat() / 10f    // base unit = 1/10 of row width

        keyRects = rows.mapIndexed { rowIdx, row ->
            val top = rowH * rowIdx + vGapHalf
            val bottom = rowH * (rowIdx + 1) - vGapHalf

            // In RTL, mirror key order so the layout reads correctly.
            val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val keysInOrder = if (isRtl) row.keys.reversed() else row.keys
            val startOffset = if (isRtl) 0f else row.startOffsetUnits * unit
            val endOffset = if (isRtl) row.startOffsetUnits * unit else 0f
            var x = if (isRtl) w.toFloat() - endOffset else startOffset

            keysInOrder.map { key ->
                val keyW = key.widthUnits * unit
                val left: Float
                val right: Float
                if (isRtl) {
                    right = x
                    left = x - keyW
                    x -= keyW
                } else {
                    left = x
                    right = x + keyW
                    x += keyW
                }
                val rect = RectF(left + hGapHalf, top, right - hGapHalf, bottom)
                Pair(key, rect)
            }
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Refresh colors from theme tokens before drawing so a light↔dark switch is reflected
        // immediately on the next frame without recreating the view.
        bgPaint.color         = theme.keyboardBg
        keyPaint.color        = theme.keyBg
        specialKeyPaint.color = theme.keySpecialBg
        pressedKeyPaint.color = theme.keyPressedBg
        textPaint.color       = theme.keyText

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (row in keyRects) {
            for ((key, rect) in row) {
                val paint = when {
                    key === pressedKey -> pressedKeyPaint
                    key.isSpecial     -> specialKeyPaint
                    else              -> keyPaint
                }
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

                if (key.code !is KeyCode.Space && key.label.isNotEmpty()) {
                    textPaint.textSize =
                        if (key.isSpecial) specialTextSizePx else keyTextSizePx
                    val textY = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(key.label, rect.centerX(), textY, textPaint)
                }
            }
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKey(x, y)
                pressedKey = key
                invalidate()
                if (key?.code == KeyCode.Backspace) startRepeat(key)
            }
            MotionEvent.ACTION_MOVE -> {
                val key = findKey(x, y)
                if (key !== pressedKey) {
                    stopRepeat()
                    pressedKey = key
                    invalidate()
                    if (key?.code == KeyCode.Backspace) startRepeat(key)
                }
            }
            MotionEvent.ACTION_UP -> {
                stopRepeat()
                val released = findKey(x, y)
                pressedKey = null
                invalidate()
                released?.let { keyListener?.onKeyPress(it) }
            }
            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                pressedKey = null
                invalidate()
            }
        }
        return true
    }

    private fun findKey(x: Float, y: Float): Key? {
        for (row in keyRects) {
            for ((key, rect) in row) {
                if (rect.contains(x, y)) return key
            }
        }
        return null
    }

    private fun startRepeat(key: Key) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                keyListener?.onKeyPress(key)
                handler.postDelayed(this, repeatInterval)
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, ViewConfiguration.getKeyRepeatTimeout().toLong())
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
