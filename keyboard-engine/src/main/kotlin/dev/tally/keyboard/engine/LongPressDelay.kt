package dev.tally.keyboard.engine

/**
 * User-selectable touch-and-hold (long-press) delay for key alternates.
 *
 * Controls how long a finger must rest on a key before its long-press tray of alternate
 * characters appears. Mirrors the "Touch and hold delay" accessibility setting on Samsung /
 * stock Android.
 *
 * [MEDIUM] is the default and equals the value that was previously hardcoded in KeyPlaneView
 * (400 ms, matching Android's own ViewConfiguration long-press timeout), so the feel is
 * unchanged for an install that never touches the setting. [SHORT] surfaces alternates faster
 * for confident typists; [LONG] guards against accidental long-presses.
 *
 * Pure JVM — no Android imports — so the timing values are unit-testable without Robolectric.
 */
enum class LongPressDelay(
    /** Milliseconds the finger must dwell before the long-press tray fires. */
    val delayMs: Long,
) {

    /** Snappy: alternates appear quickly for users who long-press deliberately. */
    SHORT(delayMs = 250L),

    /** The historical default, matching Android's ViewConfiguration long-press timeout. */
    MEDIUM(delayMs = 400L),

    /** Forgiving: a longer dwell avoids accidental trays during fast typing. */
    LONG(delayMs = 600L);

    companion object {

        /** The delay used when the preference is unset, matching pre-setting behaviour. */
        val DEFAULT = MEDIUM

        /**
         * Returns the delay matching [key], falling back to [DEFAULT] for unrecognised
         * values. [key] matches the [name] of the enum constant (case-sensitive).
         */
        fun fromKey(key: String?): LongPressDelay =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
