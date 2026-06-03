package dev.tally.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Draw-level coverage for the user key-font-scale (Stage 3).
 *
 * Drives the real measure → layout → buildGeometry path and records the [Paint.textSize] each
 * key label is drawn with via a spying canvas. The acceptance criterion is that the glyph paint
 * size reflects the scale: a plain label glyph ('q') must be drawn at baseSize × keyFontScale.
 *
 * Robolectric runs at 1× density, so the 18sp base key-text size equals 18px, which makes the
 * expected pixel sizes exact and density-independent for the assertions below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPlaneViewFontScaleTest {

    private fun newView(): KeyPlaneView {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return KeyPlaneView(ctx).apply {
            currentRows = KeyboardLayout.ALPHA_LOWER
            measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, VIEW_WIDTH, measuredHeight)
        }
    }

    @Test
    fun defaultScale_drawsLabelsAtBaseSize() {
        val view = newView()  // keyFontScale defaults to 1.0
        val recorder = recordDraw(view)
        // 'q' is a plain label drawn at the base key-text size (18sp == 18px at 1× density).
        val qSize = recorder.sizeOf("q")
        assertNotNull("'q' label must be drawn", qSize)
        assertEquals(BASE_KEY_TEXT_PX, qSize!!, 0.01f)
    }

    @Test
    fun largeScale_growsLabelGlyph() {
        val view = newView().apply { keyFontScale = 1.3f }
        val recorder = recordDraw(view)
        val qSize = recorder.sizeOf("q")
        assertNotNull("'q' label must be drawn", qSize)
        assertEquals(
            "label glyph paint size must reflect the 1.3 scale",
            BASE_KEY_TEXT_PX * 1.3f,
            qSize!!,
            0.01f,
        )
    }

    @Test
    fun smallScale_shrinksLabelGlyph() {
        val view = newView().apply { keyFontScale = 0.85f }
        val recorder = recordDraw(view)
        val qSize = recorder.sizeOf("q")
        assertNotNull("'q' label must be drawn", qSize)
        assertEquals(
            "label glyph paint size must reflect the 0.85 scale",
            BASE_KEY_TEXT_PX * 0.85f,
            qSize!!,
            0.01f,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun recordDraw(view: KeyPlaneView): DrawRecorder {
        val bmp = Bitmap.createBitmap(VIEW_WIDTH, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val recorder = DrawRecorder(bmp)
        view.draw(recorder)
        return recorder
    }

    /** Canvas that records the paint text size each drawn glyph used, keyed by glyph string. */
    private class DrawRecorder(bmp: Bitmap) : Canvas(bmp) {
        private val sizes = HashMap<String, Float>()

        fun sizeOf(text: String): Float? = sizes[text]

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            sizes[text] = paint.textSize
            super.drawText(text, x, y, paint)
        }
    }

    private companion object {
        const val VIEW_WIDTH = 1080

        // KeyPlaneView.keyTextSizePx = 18sp; Robolectric density is 1×, so sp == px.
        const val BASE_KEY_TEXT_PX = 18f
    }
}
