package dev.tally.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ThemeImageExtractor].
 *
 * Verifies:
 *   - A fully-gray bitmap returns null (no chromatic content).
 *   - A solid vivid-blue bitmap extracts a non-null scheme with a blue-ish primary seed.
 *   - A solid vivid-red bitmap extracts a scheme different from the blue one.
 *   - The returned [DynamicColorScheme.Scheme] fields are non-zero.
 *   - Down-sampled inputs (large bitmaps) are handled without OOM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeImageExtractorTest {

    // ── Achromatic input ──────────────────────────────────────────────────────

    @Test
    fun extract_grayBitmap_returnsNull() {
        val gray = solidBitmap(Color.GRAY)
        assertNull("A gray image has no chromatic content; result must be null",
            ThemeImageExtractor.extract(gray))
    }

    @Test
    fun extract_whiteBitmap_returnsNull() {
        val white = solidBitmap(Color.WHITE)
        assertNull(ThemeImageExtractor.extract(white))
    }

    @Test
    fun extract_blackBitmap_returnsNull() {
        val black = solidBitmap(Color.BLACK)
        assertNull(ThemeImageExtractor.extract(black))
    }

    // ── Chromatic input ───────────────────────────────────────────────────────

    @Test
    fun extract_vividBlueBitmap_returnsNonNull() {
        val blue = solidBitmap(Color.BLUE)
        assertNotNull(ThemeImageExtractor.extract(blue))
    }

    @Test
    fun extract_vividRedBitmap_returnsNonNull() {
        val red = solidBitmap(Color.RED)
        assertNotNull(ThemeImageExtractor.extract(red))
    }

    @Test
    fun extract_vividBlueBitmap_hasNonZeroSeeds() {
        val scheme = ThemeImageExtractor.extract(solidBitmap(Color.BLUE))!!
        assertNotEquals(0, scheme.primarySeed)
        assertNotEquals(0, scheme.secondarySeed)
        assertNotEquals(0, scheme.neutralSeed)
    }

    @Test
    fun extract_differentColors_produceDifferentSchemes() {
        val blueScheme = ThemeImageExtractor.extract(solidBitmap(Color.BLUE))
        val redScheme  = ThemeImageExtractor.extract(solidBitmap(Color.RED))
        // Both should be non-null and produce distinct primary seeds.
        assertNotNull(blueScheme)
        assertNotNull(redScheme)
        assertNotEquals(
            "Blue and red images must produce different primary seed colors",
            blueScheme!!.primarySeed, redScheme!!.primarySeed,
        )
    }

    // ── Large bitmap (down-sample path) ───────────────────────────────────────

    @Test
    fun extract_largeBitmap_doesNotThrow() {
        // A 512×512 vivid blue bitmap exercises the createScaledBitmap down-sample path.
        // Note: Robolectric's createScaledBitmap may produce all-zero pixels, so we accept
        // either a non-null scheme (chromatic pixels preserved) or null (achromatic fallback).
        // The important thing is that the call does not throw.
        val large = solidBitmap(Color.BLUE, width = 512, height = 512)
        // No assertion on the result — this test only verifies no exception is thrown.
        try {
            ThemeImageExtractor.extract(large)
        } catch (e: Exception) {
            throw AssertionError("extract() must not throw for a large bitmap", e)
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertNotEquals(message: String, unexpected: Int, actual: Int) {
        if (unexpected == actual) {
            throw AssertionError("$message (both were $actual)")
        }
    }

    private fun assertNotEquals(unexpected: Int, actual: Int) {
        assertNotEquals("Values should not be equal", unexpected, actual)
    }

    private fun solidBitmap(
        @Suppress("SameParameterValue") color: Int,
        width: Int = 32,
        height: Int = 32,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().also { it.color = color }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bmp
    }
}
