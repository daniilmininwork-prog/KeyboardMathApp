package dev.tally.keyboard.engine

/**
 * Pure-JVM state machine for the space-bar cursor-control gesture.
 *
 * A horizontal swipe on the space key moves the cursor one position per [stepPx] of travel.
 * An initial dead zone ([deadZonePx]) prevents accidental activation during normal space taps
 * or near-vertical touches that the user intends as something else.
 *
 * ### Interaction model
 * 1. Call [onSpaceDown] when the pointer lands on the space key.
 * 2. Route every subsequent MOVE event on that pointer through [onMove].
 *    [onMove] returns the number of cursor steps produced (negative = left, positive = right).
 * 3. Call [onUp] on pointer release.  Returns true when the cursor gesture was active (meaning
 *    the space character should **not** be committed — the gesture consumed the touch).
 * 4. Call [reset] whenever the pointer is cancelled or a new field is entered.
 *
 * ### Selection mode
 * When [selectionMode] is set to true before a gesture starts, [onMove] produces selection
 * extension steps rather than plain cursor moves.  The caller reads [selectionMode] to decide
 * whether to send DPAD events plain or with the SHIFT meta-state.
 *
 * ### No Android imports
 * All types are plain Kotlin / JVM.  The Android event loop and [android.view.KeyEvent]
 * constants live entirely in the IME shell layer.
 *
 * @param deadZonePx  Minimum horizontal travel before cursor steps start.  Should be at least
 *                    half a key width so tap → space still works reliably.
 * @param stepPx      Pixels of horizontal travel that produce one cursor step.
 */
class CursorController(
    val deadZonePx: Float = DEFAULT_DEAD_ZONE_PX,
    val stepPx: Float = DEFAULT_STEP_PX,
) {

    /**
     * When true the gesture extends the selection rather than moving the bare cursor.
     *
     * Set by the IME shell when the shift key is currently latched or locked.  The controller
     * itself never mutates this flag; the caller owns it.
     */
    var selectionMode: Boolean = false

    /**
     * True once the dead zone has been crossed on the current touch.
     *
     * Callers can read this to suppress normal key-press feedback (preview dismiss, haptic) as
     * soon as the gesture intent is clear, without waiting for the first step.
     */
    var gestureActive: Boolean = false
        private set

    // X coordinate at the moment the space key received ACTION_DOWN.
    private var downX: Float = 0f

    // Running total of accumulated (fractional) steps since the gesture started.  Each integer
    // crossing produces one step event.
    private var accumulatedX: Float = 0f

    // Tracks how many full steps the accumulator has already consumed so [onMove] can return
    // only the *delta* steps since the last call.
    private var lastFullSteps: Int = 0

    // True when the finger has lifted; prevents [onMove] from producing steps after UP.
    private var finished: Boolean = false

    // True after [onSpaceDown] has been called, cleared by [reset]. Prevents [onMove] from
    // producing steps if the caller sends MOVE events without a preceding DOWN (e.g. after cancel).
    private var armed: Boolean = false

    /**
     * Register the moment the space key received a finger-down event.
     *
     * @param x The pointer's x-coordinate in the key plane's pixel space.
     */
    fun onSpaceDown(x: Float) {
        downX = x
        accumulatedX = 0f
        lastFullSteps = 0
        gestureActive = false
        finished = false
        armed = true
    }

    /**
     * Feed a MOVE sample to the controller.
     *
     * Returns the number of new cursor steps to fire this call.  Negative values mean
     * move-left (or select-left), positive mean move-right (or select-right).  Zero means
     * no new steps since the last call (still inside dead zone or no full-step boundary crossed).
     *
     * @param x The pointer's current x-coordinate.
     */
    fun onMove(x: Float): Int {
        if (finished || !armed) return 0

        val totalDeltaX = x - downX

        // Stay silent until the dead zone is crossed.
        if (!gestureActive) {
            if (kotlin.math.abs(totalDeltaX) < deadZonePx) return 0
            gestureActive = true
            // Seed the accumulator at the dead-zone edge so the first step fires immediately
            // on crossing, not after an additional stepPx of travel.
            val sign = if (totalDeltaX > 0) 1f else -1f
            accumulatedX = (totalDeltaX - sign * deadZonePx)
        } else {
            accumulatedX = totalDeltaX - (if (totalDeltaX > 0) deadZonePx else -deadZonePx)
        }

        val fullStepsNow = (accumulatedX / stepPx).toInt()
        val delta = fullStepsNow - lastFullSteps
        lastFullSteps = fullStepsNow
        return delta
    }

    /**
     * Notify the controller that the pointer lifted.
     *
     * Returns true if the cursor gesture was active (dead zone was crossed), indicating that
     * the touch should be treated as a gesture and the normal space commit suppressed.
     * Returns false for a normal tap (no significant horizontal movement detected).
     */
    fun onUp(): Boolean {
        finished = true
        return gestureActive
    }

    /**
     * Reset the controller to its initial state.
     *
     * Call when the pointer is cancelled ([MotionEvent.ACTION_CANCEL]) or on field entry.
     */
    fun reset() {
        downX = 0f
        accumulatedX = 0f
        lastFullSteps = 0
        gestureActive = false
        finished = false
        armed = false
    }

    companion object {
        /**
         * Minimum horizontal travel (in the same pixel space as the key plane) before the gesture
         * activates.  Chosen to be large enough that a slightly-off tap on the space key does not
         * accidentally nudge the cursor, but small enough that intentional cursor panning feels
         * immediate.  Callers that construct the controller with a density-aware value should
         * scale 24 dp to pixels.
         */
        const val DEFAULT_DEAD_ZONE_PX = 24f

        /**
         * Horizontal travel that produces one cursor step.  At ~160 dpi a comfortable scan pace
         * is one character per 16 dp (~26 px); this value is intentionally small so the gesture
         * feels responsive rather than requiring a long drag per step.  Scale to pixels via density
         * at construction time.
         */
        const val DEFAULT_STEP_PX = 16f
    }
}
