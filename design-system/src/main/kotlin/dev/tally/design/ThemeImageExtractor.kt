package dev.tally.design

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Extracts a tonal palette seed from an arbitrary [Bitmap].
 *
 * Used by the "custom image" path in the theme settings: the user picks an image and we derive a
 * [DynamicColorScheme.Scheme] that shifts the keyboard accent toward the image's dominant hue.
 *
 * ## Algorithm
 *
 * The image is down-sampled to a tiny thumbnail (16×16 px) before analysis so that processing is
 * bounded and fast regardless of the source resolution. The thumbnail pixels are converted to HSV
 * and weighted by saturation × value — desaturated grays and near-blacks contribute little to the
 * dominant hue. The weighted-average hue is converted back to an ARGB seed.
 *
 * This is intentionally simple: it produces a single representative hue rather than a full tonal
 * palette. The downstream [KeyTheme.applyDynamicColors] softens the seed with alpha blending, so
 * harsh or high-saturation inputs are automatically tamed.
 *
 * No network access, no external library, no disk I/O — extraction is a pure in-memory transform.
 */
object ThemeImageExtractor {

    private const val THUMB_SIZE = 16

    /**
     * Derives a [DynamicColorScheme.Scheme] from [bitmap]'s dominant hue.
     *
     * @param bitmap source image in any configuration; may be recycled after this call returns.
     * @return a scheme whose [DynamicColorScheme.Scheme.primarySeed] encodes the dominant hue,
     *         or null if the image has no meaningful chromatic content (all grays/blacks/whites).
     */
    fun extract(bitmap: Bitmap): DynamicColorScheme.Scheme? {
        val thumb = scaledThumb(bitmap)
        val seed = dominantHueSeed(thumb) ?: return null
        // Use the primary as a tinted secondary (slightly shifted) and a muted neutral.
        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        hsv[0] = (hsv[0] + 30f) % 360f   // secondary: +30° hue rotation
        val secondary = Color.HSVToColor(hsv)
        hsv[0] = (hsv[0] + 30f) % 360f   // neutral: another +30°, lower saturation
        hsv[1] = hsv[1] * 0.3f
        val neutral = Color.HSVToColor(hsv)
        return DynamicColorScheme.Scheme(
            primarySeed   = seed,
            secondarySeed = secondary,
            neutralSeed   = neutral,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun scaledThumb(src: Bitmap): Bitmap {
        if (src.width <= THUMB_SIZE && src.height <= THUMB_SIZE) return src
        return Bitmap.createScaledBitmap(src, THUMB_SIZE, THUMB_SIZE, /* filter= */ true)
    }

    /**
     * Returns the dominant hue as a fully-opaque ARGB seed, or null when all pixels are achromatic.
     *
     * Pixels with saturation < 0.1 (near-gray) or value < 0.1 (near-black) are excluded from the
     * weighted average because they carry no meaningful hue information.
     */
    @ColorInt
    private fun dominantHueSeed(thumb: Bitmap): Int? {
        val w = thumb.width
        val h = thumb.height
        val pixels = IntArray(w * h)
        thumb.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsv = FloatArray(3)
        var sinSum = 0.0
        var cosSum = 0.0
        var totalWeight = 0.0

        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            val value      = hsv[2]
            if (saturation < 0.1f || value < 0.1f) continue
            val weight    = saturation * value
            val hueRad    = Math.toRadians(hsv[0].toDouble())
            sinSum       += Math.sin(hueRad) * weight
            cosSum       += Math.cos(hueRad) * weight
            totalWeight  += weight
        }

        if (totalWeight < 0.01) return null  // no chromatic content

        val dominantHueDeg = (Math.toDegrees(Math.atan2(sinSum, cosSum)) + 360.0) % 360.0
        // Build a vivid seed at full saturation/value so [KeyTheme.applyDynamicColors] sees a
        // hue-pure input and can decide the final opacity/brightness via alpha blending.
        hsv[0] = dominantHueDeg.toFloat()
        hsv[1] = 0.80f
        hsv[2] = 0.90f
        return Color.HSVToColor(hsv)
    }
}
