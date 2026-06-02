package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KeyboardHeightPolicy].
 *
 * All tests use exact pixel arithmetic — no Android runtime required.
 */
class KeyboardHeightPolicyTest {

    // Typical phone metrics: 1080 × 2400 px at 2.75× density (roughly 393 × 873 dp).
    private val PORTRAIT_W  = 1080
    private val PORTRAIT_H  = 2400
    private val DENSITY     = 2.75f
    private val ROW_COUNT   = 4

    @Test
    fun portrait_usesPortraitRowHeight() {
        val expectedRowH = (KeyboardHeightPolicy.ROW_HEIGHT_DP_PORTRAIT * DENSITY).toInt()
        val expected = expectedRowH * ROW_COUNT

        val result = KeyboardHeightPolicy.heightPx(PORTRAIT_W, PORTRAIT_H, DENSITY, ROW_COUNT)

        assertEquals(expected, result)
    }

    @Test
    fun landscape_usesLandscapeRowHeight() {
        // Swap width and height to simulate landscape.
        val result = KeyboardHeightPolicy.heightPx(PORTRAIT_H, PORTRAIT_W, DENSITY, ROW_COUNT)

        val expectedRowH = (KeyboardHeightPolicy.ROW_HEIGHT_DP_LANDSCAPE * DENSITY).toInt()
        val expected = expectedRowH * ROW_COUNT
        assertEquals(expected, result)
    }

    @Test
    fun portrait_doesNotExceedScreenFraction() {
        val result = KeyboardHeightPolicy.heightPx(PORTRAIT_W, PORTRAIT_H, DENSITY, ROW_COUNT)

        val cap = (PORTRAIT_H * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()
        assertTrue(result <= cap) { "Height $result must be ≤ screen cap $cap" }
    }

    @Test
    fun smallScreen_cappedByFraction() {
        // 480 × 800 @ 1.5× — very small screen where portrait rows would exceed 45%.
        val w = 480
        val h = 800
        val density = 1.5f
        val rows = 4

        val result = KeyboardHeightPolicy.heightPx(w, h, density, rows)
        val cap = (h * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()

        assertTrue(result <= cap) { "Height on small screen must be ≤ cap" }
    }

    @Test
    fun singleRow_producesRowHeight() {
        val expectedRowH = (KeyboardHeightPolicy.ROW_HEIGHT_DP_PORTRAIT * DENSITY).toInt()

        val result = KeyboardHeightPolicy.heightPx(PORTRAIT_W, PORTRAIT_H, DENSITY, rowCount = 1)

        assertEquals(expectedRowH, result)
    }

    @Test
    fun fiveRows_portraitStillCapped() {
        // A 5-row layout in portrait on a large screen.
        val density = 3.5f
        val result = KeyboardHeightPolicy.heightPx(1440, 3200, density, rowCount = 5)

        val cap = (3200 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()
        assertTrue(result <= cap) { "5-row height must be ≤ screen cap" }
    }

    @Test
    fun landscapeSquareScreen_treatedAsPortrait() {
        // Exact square: width == height → not landscape (landscape is width > height).
        val result = KeyboardHeightPolicy.heightPx(1080, 1080, DENSITY, ROW_COUNT)

        val expectedRowH = (KeyboardHeightPolicy.ROW_HEIGHT_DP_PORTRAIT * DENSITY).toInt()
        val expected = (expectedRowH * ROW_COUNT).coerceAtMost(
            (1080 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()
        )
        assertEquals(expected, result)
    }

    @Test
    fun result_isAlwaysPositive() {
        // Even with 1-px dimensions the result must be ≥ 1.
        val result = KeyboardHeightPolicy.heightPx(1, 1, density = 1f, rowCount = 1)
        assertTrue(result >= 1) { "Height must always be positive" }
    }

    @Test
    fun landscape_smallScreen_capped() {
        // Landscape on a small screen: 800 × 480 @ 1.5×.
        val result = KeyboardHeightPolicy.heightPx(800, 480, 1.5f, 4)
        val cap = (480 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()
        assertTrue(result <= cap) { "Landscape small-screen height must be ≤ cap" }
    }

    @Test
    fun numberRowAdded_fiveRows_landscape() {
        // With number row enabled, rowCount = 5 in landscape.
        val result = KeyboardHeightPolicy.heightPx(PORTRAIT_H, PORTRAIT_W, DENSITY, rowCount = 5)
        val cap = (PORTRAIT_W * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()
        assertTrue(result <= cap) { "5-row landscape height must be ≤ cap" }
        assertTrue(result > 0) { "5-row landscape height must be > 0" }
    }

    @Test
    fun portraitAndLandscape_landscapeIsShorter() {
        val portrait  = KeyboardHeightPolicy.heightPx(PORTRAIT_W, PORTRAIT_H, DENSITY, ROW_COUNT)
        val landscape = KeyboardHeightPolicy.heightPx(PORTRAIT_H, PORTRAIT_W, DENSITY, ROW_COUNT)

        assertTrue(landscape < portrait) { "Landscape keyboard must be shorter than portrait" }
    }
}
