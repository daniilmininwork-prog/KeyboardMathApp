package dev.tally.overlay

import dev.tally.overlay.OverlayPlacement.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OverlayPlacement.compute] — the AC-5 occlusion logic (TO.4, `02 §4.5`).
 *
 * Pure JVM: no Android framework. The window glue (resolving `WindowInsets`) is covered on
 * device, but the placement decision itself is fully exercised here.
 *
 * Coordinate convention: y grows downward; `contentTop` is just below the status bar / cutout,
 * `contentBottom` is just above the keyboard / nav bar. A typical phone band is ~[72, 1400].
 */
class OverlayPlacementTest {

    private val chipH = 132   // ~44dp at 3x
    private val margin = 24   // ~8dp at 3x

    @Test
    fun `chip sits above the field when there is room`() {
        // Field mid-screen; plenty of space above for the chip.
        val p = OverlayPlacement.compute(
            fieldTop = 800, fieldBottom = 880,
            contentTop = 72, contentBottom = 1400,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Show(800 - chipH - margin, below = false), p)
    }

    @Test
    fun `chip drops below the field when there is no room above without crossing the status bar`() {
        // Field hugs the top of the content band — above placement would cross the status bar/notch.
        val fieldTop = 80
        val fieldBottom = 200
        val p = OverlayPlacement.compute(
            fieldTop = fieldTop, fieldBottom = fieldBottom,
            contentTop = 72, contentBottom = 1400,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Show(fieldBottom + margin, below = true), p)
    }

    @Test
    fun `chip is never placed over the status bar or notch`() {
        // Whichever placement is chosen, its top edge must be >= contentTop.
        val contentTop = 120
        val p = OverlayPlacement.compute(
            fieldTop = 130, fieldBottom = 260,
            contentTop = contentTop, contentBottom = 1400,
            chipHeight = chipH, margin = margin,
        )
        assertTrue(p is Placement.Show)
        assertTrue("chip top must clear the status bar/notch", (p as Placement.Show).y >= contentTop)
    }

    @Test
    fun `chip is suppressed when the keyboard would occlude it`() {
        // Field is near the bottom and the keyboard pushes contentBottom up: no room above
        // (field too close to status bar) and below would land under the keyboard.
        val p = OverlayPlacement.compute(
            fieldTop = 90, fieldBottom = 150,
            contentTop = 72, contentBottom = 200,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Suppress, p)
    }

    @Test
    fun `chip is suppressed when the field itself is below the keyboard top`() {
        // Field is occluded (fieldTop beyond contentBottom). Neither placement is fully visible.
        val p = OverlayPlacement.compute(
            fieldTop = 1300, fieldBottom = 1380,
            contentTop = 72, contentBottom = 1000,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Suppress, p)
    }

    @Test
    fun `below placement is rejected when chip bottom would dip under the keyboard`() {
        // Above is impossible (field at top); below fits vertically only if chip bottom <= contentBottom.
        val fieldBottom = 900
        val contentBottom = fieldBottom + margin + chipH - 1   // one px short
        val p = OverlayPlacement.compute(
            fieldTop = 80, fieldBottom = fieldBottom,
            contentTop = 72, contentBottom = contentBottom,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Suppress, p)
    }

    @Test
    fun `below placement is accepted at the exact boundary`() {
        val fieldBottom = 900
        val contentBottom = fieldBottom + margin + chipH   // exactly fits
        val p = OverlayPlacement.compute(
            fieldTop = 80, fieldBottom = fieldBottom,
            contentTop = 72, contentBottom = contentBottom,
            chipHeight = chipH, margin = margin,
        )
        assertEquals(Placement.Show(fieldBottom + margin, below = true), p)
    }

    @Test
    fun `above placement is preferred over below when both fit`() {
        val p = OverlayPlacement.compute(
            fieldTop = 700, fieldBottom = 760,
            contentTop = 72, contentBottom = 1400,
            chipHeight = chipH, margin = margin,
        )
        assertTrue(p is Placement.Show)
        assertEquals(false, (p as Placement.Show).below)
    }
}
