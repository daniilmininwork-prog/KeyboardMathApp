package dev.tally.overlay

/**
 * Pure placement arithmetic for the result chip (TO.4, `02 §4.5`).
 *
 * The chip is anchored relative to the focused field but must stay inside the visible content
 * band — below the status bar / display cutout ([contentTop]) and above the keyboard / navigation
 * bar ([contentBottom]). It prefers sitting just above the field; if that would cross the status
 * bar or notch it tries just below; if neither leaves the chip fully visible it is **suppressed**
 * rather than shown un-tappable (AC-5).
 *
 * Kept free of Android types so the occlusion logic is unit-testable on the JVM; the window layer
 * ([OverlayChipWindow]) resolves the inset values from `WindowInsets` and feeds them in.
 */
internal object OverlayPlacement {

    sealed interface Placement {
        /** Show the chip with its top edge at [y]; [below] is true when anchored under the field. */
        data class Show(val y: Int, val below: Boolean) : Placement

        /** No fully-visible, tappable position exists — draw nothing. */
        data object Suppress : Placement
    }

    /**
     * @param fieldTop top edge of the focused field, screen px
     * @param fieldBottom bottom edge of the focused field, screen px
     * @param contentTop first y the chip may occupy without overlapping the status bar / cutout
     * @param contentBottom exclusive lower bound; the chip's bottom edge must not exceed it
     *   (this is the keyboard's top / the navigation bar, whichever is higher)
     * @param chipHeight chip height, px
     * @param margin gap between chip and field, px
     */
    fun compute(
        fieldTop: Int,
        fieldBottom: Int,
        contentTop: Int,
        contentBottom: Int,
        chipHeight: Int,
        margin: Int,
    ): Placement {
        // Prefer just above the field, but only if the whole chip clears the status bar / notch
        // and does not spill into the keyboard band.
        val aboveY = fieldTop - chipHeight - margin
        if (aboveY >= contentTop && aboveY + chipHeight <= contentBottom) {
            return Placement.Show(aboveY, below = false)
        }

        // Otherwise drop below the field, again requiring the whole chip to be visible.
        val belowY = fieldBottom + margin
        if (belowY >= contentTop && belowY + chipHeight <= contentBottom) {
            return Placement.Show(belowY, below = true)
        }

        return Placement.Suppress
    }
}
