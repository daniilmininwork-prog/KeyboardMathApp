package dev.tally.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Draw-level coverage for the alternate-character keycap hints (Stage 1).
 *
 * Drives the real measure → layout → buildGeometry path (like [KeyPlaneViewIntegrationTest]) and
 * records every [Canvas.drawText] call through a spying canvas. The hint glyphs are drawn at a
 * smaller text size than the main labels, so a draw of a key's *first* moreKey at the hint size is
 * an unambiguous signal that the hint was rendered for that key.
 *
 * Asserts the two acceptance criteria:
 *   - Hints are drawn only when [KeyPlaneView.altCharHints] is enabled.
 *   - Hints are drawn only for keys that carry moreKeys (never for plain keys like 'q').
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPlaneViewAltHintTest {

    private lateinit var view: KeyPlaneView

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)
        view.currentRows = KeyboardLayout.ALPHA_LOWER
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, VIEW_WIDTH, view.measuredHeight)
    }

    @Test
    fun hintsNotDrawn_whenDisabled() {
        view.altCharHints = false
        val recorder = recordDraw()

        // 'e' carries accent alternates (é, è, …). With hints off, its first alternate must not appear.
        val firstAlt = KeyboardLayout.ALPHA_LOWER[0].keys.first { it.code == KeyCode.Char('e') }
            .moreKeys.first()
        assertFalse(
            "hint glyph '$firstAlt' must not be drawn when altCharHints is off",
            recorder.hintGlyphs.contains(firstAlt),
        )
    }

    @Test
    fun hintsDrawn_onlyForKeysWithMoreKeys_whenEnabled() {
        view.altCharHints = true
        val recorder = recordDraw()

        // Keys with moreKeys: their first alternate must be present at the hint (small) size.
        val keysWithAlts = KeyboardLayout.ALPHA_LOWER.flatMap { it.keys }.filter { it.moreKeys.isNotEmpty() }
        assertTrue("layout must have keys with alternates to make this test meaningful", keysWithAlts.isNotEmpty())
        for (key in keysWithAlts) {
            val firstAlt = key.moreKeys.first()
            assertTrue(
                "hint glyph '$firstAlt' for key '${key.label}' must be drawn when enabled",
                recorder.hintGlyphs.contains(firstAlt),
            )
        }

        // A plain key with no alternates ('q') must never emit a hint glyph. 'q' itself is drawn as a
        // full-size label, so it must appear among the main labels but not among the hint-sized draws.
        assertTrue("'q' label must still be drawn at full size", recorder.mainGlyphs.contains("q"))
        assertFalse("'q' must not be drawn at the hint size", recorder.hintGlyphs.contains("q"))
    }

    @Test
    fun hintCount_matchesKeysWithMoreKeys_whenEnabled() {
        view.altCharHints = true
        val recorder = recordDraw()

        val expected = KeyboardLayout.ALPHA_LOWER.flatMap { it.keys }.count { it.moreKeys.isNotEmpty() }
        assertEquals(
            "exactly one hint per key carrying moreKeys",
            expected,
            recorder.hintDrawCount,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun recordDraw(): DrawRecorder {
        val bmp = Bitmap.createBitmap(VIEW_WIDTH, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val recorder = DrawRecorder(bmp)
        view.draw(recorder)
        return recorder
    }

    /**
     * Canvas that records drawText calls, bucketed by text size so hint glyphs (smaller) are told
     * apart from full-size key labels. The hint size is keyTextSize × HINT_TEXT_SCALE; anything
     * strictly below the smallest full label size is treated as a hint.
     */
    private class DrawRecorder(bmp: Bitmap) : Canvas(bmp) {
        val hintGlyphs = mutableListOf<String>()
        val mainGlyphs = mutableListOf<String>()
        var hintDrawCount = 0

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            // The hint paint uses keyTextSize × 0.55; full labels use keyTextSize (18sp) or the
            // special size (14sp). The hint size (≈9.9sp) is below both, giving a clean split.
            if (paint.textSize < HINT_SIZE_THRESHOLD_PX) {
                hintGlyphs += text
                hintDrawCount++
            } else {
                mainGlyphs += text
            }
            super.drawText(text, x, y, paint)
        }
    }

    private companion object {
        const val VIEW_WIDTH = 1080

        // Robolectric runs at 1× density, so sp == px. The smallest full label is the 14sp special
        // size; the hint size is 18 × 0.55 ≈ 9.9sp. 12px sits cleanly between the two buckets.
        const val HINT_SIZE_THRESHOLD_PX = 12f

        // Kept in sync with KeyPlaneView.HINT_TEXT_SCALE; referenced only for the comment above.
        const val HINT_TEXT_SCALE = 0.55f
    }
}
