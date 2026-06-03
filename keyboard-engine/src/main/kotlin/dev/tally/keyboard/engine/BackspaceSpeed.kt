package dev.tally.keyboard.engine

/**
 * User-selectable backspace key-repeat speed.
 *
 * Each option bundles the three constants that drive the accelerating delete schedule
 * (initial delay, acceleration step, and minimum interval) so the whole "feel" of held
 * backspace changes as one coherent unit rather than three independent sliders.
 *
 * [NORMAL] reproduces the previously hardcoded behaviour exactly (400 / 0.80 / 50), so an
 * existing install that has never touched the setting deletes at the same pace as before.
 * [SLOW] gives more reaction time before runaway deletion; [FAST] reaches the floor sooner
 * and deletes more aggressively for power users.
 *
 * Pure JVM — no Android imports — so it lives in keyboard-engine alongside
 * [KeyRepeatController], which consumes these values.
 */
enum class BackspaceSpeed(
    /** Delay before the first repeat fires while the key is held. */
    val initialDelayMs: Long,
    /** Multiplicative factor applied each repeat (< 1 to shrink toward the floor). */
    val stepFactor: Double,
    /** Floor interval once the schedule has fully accelerated. */
    val minIntervalMs: Long,
) {

    /**
     * Gentle pace: a longer wait before repeats start and a higher floor so a held key
     * never empties a field faster than the eye can follow.
     */
    SLOW(initialDelayMs = 550L, stepFactor = 0.85, minIntervalMs = 90L),

    /**
     * The historical default; matches the constants that were hardcoded in
     * [KeyRepeatController] before this setting existed.
     */
    NORMAL(initialDelayMs = 400L, stepFactor = 0.80, minIntervalMs = 50L),

    /**
     * Aggressive pace: repeats start sooner, accelerate harder, and bottom out at a tight
     * floor for users who routinely delete long runs of text.
     */
    FAST(initialDelayMs = 250L, stepFactor = 0.72, minIntervalMs = 30L);

    /** Builds a fresh [KeyRepeatController] configured for this speed. */
    fun newController(): KeyRepeatController =
        KeyRepeatController(
            initialDelayMs = initialDelayMs,
            minIntervalMs  = minIntervalMs,
            stepFactor     = stepFactor,
        )

    companion object {

        /** The speed used when the preference is unset, matching pre-setting behaviour. */
        val DEFAULT = NORMAL

        /**
         * Returns the speed matching [key], falling back to [DEFAULT] for unrecognised
         * values. [key] matches the [name] of the enum constant (case-sensitive); preference
         * values are persisted as the enum's [name] and the constants are kept stable.
         */
        fun fromKey(key: String?): BackspaceSpeed =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
