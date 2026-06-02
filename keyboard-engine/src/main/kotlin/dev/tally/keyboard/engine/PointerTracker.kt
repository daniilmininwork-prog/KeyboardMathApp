package dev.tally.keyboard.engine

/**
 * Per-finger state machine for a single active pointer in the keyboard touch layer.
 *
 * One instance is allocated per active pointer ID as soon as an ACTION_DOWN or
 * ACTION_POINTER_DOWN event arrives, and released when the matching UP or CANCEL
 * fires. The tracker is intentionally free of Android types so it can be unit-tested
 * without Robolectric.
 *
 * Rollover: if the finger slides from the key it originally pressed onto a different
 * key, the tracker records the new key as [currentKey]. The key emitted on UP is
 * [currentKey], not [downKey] — this is the standard keyboard rollover contract.
 * Key-down events are fired at [downKey] on construction; no key-down is fired for
 * the rollover destination (matching AOSP PointerTracker behaviour).
 *
 * @param pointerId  The Android pointer ID, unique within the lifetime of a gesture.
 * @param downKey    The [KeyId] that was under the pointer when DOWN was received,
 *                   or null if the pointer landed in the inter-key gap.
 * @param geometry   The [KeyGeometry] active when this tracker was created; used for
 *                   subsequent rollover hit-testing.
 */
class PointerTracker(
    val pointerId: Int,
    val downKey: KeyId?,
    private val geometry: KeyGeometry,
) {

    /**
     * The key most recently under this pointer.
     *
     * Updated on every MOVE event via [onMove]. Starts equal to [downKey]. When the
     * pointer leaves the keyboard area the last valid key is retained so UP still
     * emits a key-up event.
     */
    var currentKey: KeyId? = downKey
        private set

    /**
     * Path of (x, y) samples accumulated since DOWN, in the geometry's pixel space.
     *
     * Reserved for future gesture/glide decode; callers can pass this to a
     * [GestureDecoder] once glide is wired in T4.1. The list is a mutable snapshot
     * owned exclusively by this tracker.
     */
    val slidePath: MutableList<FloatArray> = mutableListOf()

    init {
        // Seed the path with the initial contact point if the geometry was able to
        // resolve a key; the x/y are not stored at this level — the caller records them.
    }

    /**
     * Updates [currentKey] based on the pointer's new position.
     *
     * Call this from ACTION_MOVE processing for every live tracker. Returns the new
     * [KeyId] under the pointer (or null if the pointer is in a gap).
     */
    fun onMove(x: Float, y: Float): KeyId? {
        val hit = geometry.keys.firstOrNull { it.contains(x, y) }?.id
        currentKey = hit ?: currentKey  // retain last valid key when sliding off-board
        return hit
    }

    /**
     * Records a position sample into [slidePath] for potential gesture use.
     *
     * This is intentionally separate from [onMove] so that recording is optional
     * and callers can batch the append without duplicating the hit-test logic.
     */
    fun recordPoint(x: Float, y: Float) {
        slidePath += floatArrayOf(x, y)
    }

    /**
     * Called when the pointer is lifted (ACTION_UP / ACTION_POINTER_UP).
     *
     * Returns the [KeyId] that should receive the key-up / commit event. This is
     * [currentKey] (the rollover destination), not necessarily [downKey].
     */
    fun onUp(): KeyId? = currentKey
}
