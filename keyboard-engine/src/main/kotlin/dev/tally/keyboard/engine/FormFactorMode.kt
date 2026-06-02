package dev.tally.keyboard.engine

/**
 * Discrete form-factor layout modes the keyboard can operate in.
 *
 * All modes are pure geometry transforms over a single shared key model — there is no
 * separate keyboard implementation per mode. The actual view-layer transformation is
 * computed by [FormFactorTransform.resolve] from the active mode and the available
 * viewport dimensions.
 *
 * Spec ref: 04 P1-7 "View-layout transforms over one key model".
 */
enum class FormFactorMode {

    /**
     * Standard full-width keyboard, anchored to the bottom of the screen.
     *
     * This is the default mode and requires no geometric adjustment — the key plane
     * occupies the full available width.
     */
    NORMAL,

    /**
     * Condensed half-width keyboard shifted to the left or right edge.
     *
     * Reduces the key plane to approximately half the screen width and positions it
     * flush to either the left or right edge. The unused half-width stays empty so the
     * user can see content and reach the keyboard comfortably with one thumb.
     *
     * The preferred side (left vs right) is stored separately in [FormFactorPrefs].
     */
    ONE_HANDED,

    /**
     * Keyboard split into two halves aligned to opposite screen edges.
     *
     * Each half receives approximately half of the total available key columns. The gap
     * between the halves is centred on the screen. Useful on larger phones and tablets
     * where a single-block keyboard forces the thumbs to reach the centre keys.
     */
    SPLIT,

    /**
     * Compact floating panel that can be repositioned anywhere on screen.
     *
     * The key plane is displayed at a reduced width and height, overlaying content
     * rather than pushing it up. Position is controlled by an (x, y) offset stored in
     * [FormFactorPrefs] and adjusted via long-press drag on the keyboard handle.
     *
     * The IME window must not claim insets in this mode so the host content is not
     * scrolled away from view — see [FormFactorTransform.claimsInsets].
     */
    FLOATING;

    companion object {

        /**
         * Returns the mode matching [key], falling back to [NORMAL] for unrecognised values.
         *
         * [key] matches the [name] of the enum constant (case-sensitive). This is stable
         * because preference values are written as the enum's [name] and the enum constants
         * are intentionally kept stable.
         */
        fun fromKey(key: String): FormFactorMode =
            entries.firstOrNull { it.name == key } ?: NORMAL
    }
}
