package dev.tally.keyboard.engine

/**
 * Manages the set of live [PointerTracker]s for a keyboard touch session.
 *
 * The pool is created once per keyboard view instance and reused across gestures.
 * Each incoming touch event is routed through [onDown], [onMove], [onUp], or [onCancel].
 *
 * All methods are designed to be called on the main/UI thread only and are not
 * thread-safe. The type is pure JVM; no Android types are imported.
 *
 * Rollover contract: fingers may cross freely. When pointer A moves onto the key that
 * pointer B originally pressed, each tracker reports its own current key independently.
 * There is no key-locking between pointers.
 */
class PointerTrackerPool {

    private val trackers: MutableMap<Int, PointerTracker> = LinkedHashMap()

    /**
     * A snapshot of all currently active pointers.
     *
     * Read-only view; safe to iterate while the pool is stable (i.e., not during
     * a concurrent mutation — which cannot happen on the single UI thread).
     */
    val activeTrackers: Collection<PointerTracker> get() = trackers.values

    /**
     * Called when a new finger contacts the screen (ACTION_DOWN or ACTION_POINTER_DOWN).
     *
     * Creates a fresh [PointerTracker] for [pointerId] and seeds it with the hit key.
     * If a tracker with [pointerId] already exists (which should not happen in a well-formed
     * stream, but can occur on some buggy OEM drivers) it is replaced.
     *
     * @param pointerId  From [MotionEvent.getPointerId].
     * @param x          Touch x in the view's coordinate space.
     * @param y          Touch y in the view's coordinate space.
     * @param geometry   Current [KeyGeometry] used for hit-testing.
     * @return The [KeyId] under the pointer, or null if in the gap.
     */
    fun onDown(pointerId: Int, x: Float, y: Float, geometry: KeyGeometry): KeyId? {
        val downKey = geometry.keys.firstOrNull { it.contains(x, y) }?.id
        val tracker = PointerTracker(pointerId, downKey, geometry)
        tracker.recordPoint(x, y)
        trackers[pointerId] = tracker
        return downKey
    }

    /**
     * Called for every position sample in ACTION_MOVE.
     *
     * ACTION_MOVE may batch samples for all active pointers; callers must iterate
     * every [MotionEvent.pointerCount] and call this once per pointer per historical
     * sample. Returns the new [KeyId] under [pointerId], or null if in a gap.
     *
     * Missing trackers (pointer appeared without a prior DOWN) are ignored.
     */
    fun onMove(pointerId: Int, x: Float, y: Float): KeyId? {
        val tracker = trackers[pointerId] ?: return null
        tracker.recordPoint(x, y)
        return tracker.onMove(x, y)
    }

    /**
     * Called when a finger is lifted (ACTION_UP or ACTION_POINTER_UP).
     *
     * Returns the [KeyId] that should be committed for [pointerId] (the rollover
     * destination at time of lift), then removes the tracker.
     *
     * Returns null if no tracker exists for [pointerId].
     */
    fun onUp(pointerId: Int): KeyId? {
        val tracker = trackers.remove(pointerId) ?: return null
        return tracker.onUp()
    }

    /**
     * Tears down all active trackers without emitting any key events.
     *
     * Must be called on ACTION_CANCEL (e.g., the parent view steals the gesture
     * for a scroll). All in-progress presses are silently discarded.
     */
    fun onCancel() {
        trackers.clear()
    }

    /** Returns true when no fingers are currently tracked. */
    fun isEmpty(): Boolean = trackers.isEmpty()

    /** Number of currently active pointers. */
    val size: Int get() = trackers.size

    /** Returns the tracker for [pointerId], or null if not active. */
    fun trackerFor(pointerId: Int): PointerTracker? = trackers[pointerId]
}
