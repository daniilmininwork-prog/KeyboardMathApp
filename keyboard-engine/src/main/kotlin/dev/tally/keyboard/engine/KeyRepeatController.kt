package dev.tally.keyboard.engine

/**
 * Accelerating repeat schedule for a held key.
 *
 * Produces a sequence of intervals (in milliseconds) for each successive repeat fire.
 * The first repeat fires after [initialDelayMs]; each subsequent fire arrives sooner
 * until the floor [minIntervalMs] is reached and held for the remainder of the press.
 *
 * Callers use [nextIntervalMs] to schedule one timer at a time. When the finger lifts
 * or the gesture is cancelled the sequence is simply abandoned — no extra state is needed.
 *
 * This class is pure JVM with no Android imports; the actual Handler/Runnable scheduling
 * is owned by the Android shell layer (KeyPlaneView) so it can be unit-tested here
 * without Robolectric.
 *
 * Typical use:
 *   val schedule = KeyRepeatController()
 *   schedule.nextIntervalMs() → 400
 *   schedule.nextIntervalMs() → 320
 *   ...
 *   schedule.nextIntervalMs() → 50   (floor reached; stays here)
 *
 * @param initialDelayMs  Delay before the first repeat fires; matches AOSP's key-repeat
 *                        initial delay (400 ms by default).
 * @param minIntervalMs   Minimum interval once the schedule has fully accelerated.
 * @param stepFactor      Multiplicative factor applied each repeat (< 1 to shrink).
 */
class KeyRepeatController(
    val initialDelayMs: Long = INITIAL_DELAY_MS,
    val minIntervalMs: Long  = MIN_INTERVAL_MS,
    val stepFactor: Double   = STEP_FACTOR,
) {
    private var fireCount = 0

    /**
     * Returns the interval (ms) to wait before the next repeat fire and advances
     * the internal counter.
     *
     * The first call returns [initialDelayMs]; each subsequent call applies [stepFactor]
     * until [minIntervalMs] is reached.
     */
    fun nextIntervalMs(): Long {
        val interval = if (fireCount == 0) {
            initialDelayMs
        } else {
            val raw = initialDelayMs * Math.pow(stepFactor, fireCount.toDouble())
            raw.toLong().coerceAtLeast(minIntervalMs)
        }
        fireCount++
        return interval
    }

    /** Resets the counter so a fresh press starts the schedule from the beginning. */
    fun reset() {
        fireCount = 0
    }

    companion object {
        /** Delay before first repeat, matching standard Android ViewConfiguration. */
        const val INITIAL_DELAY_MS = 400L

        /** Floor reached after ~8 firings at the default step factor. */
        const val MIN_INTERVAL_MS = 50L

        /** Each repeat interval is 80% of the previous one. */
        const val STEP_FACTOR = 0.80
    }
}
