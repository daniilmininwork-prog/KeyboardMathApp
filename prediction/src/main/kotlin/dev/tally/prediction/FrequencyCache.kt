package dev.tally.prediction

import kotlin.math.log2

/**
 * Per-user word frequency table for personalization.
 *
 * Tracks how often the user commits each word and converts the accumulated count into
 * a log-probability boost that the [BeamDecoder] adds to each candidate's combined
 * score.  The boost is always non-negative and proportional to the log of the relative
 * frequency, so common user words are ranked higher without overwhelming the base LM.
 *
 * Thread-safety: all public mutating methods are synchronized on [this] so the cache
 * can be written from the IME's bg executor and read from the decoder worker without
 * additional locking in the caller.
 *
 * Persistence: this class is in-memory only. The owning module is responsible for
 * serialising [snapshot] to durable storage and reconstructing via [loadFrom] on
 * the next session. The [FieldPolicy.persistAllowed] and [FieldPolicy.learningEnabled]
 * gates are enforced by [WordPredictorImpl] before calling [record] — this class
 * applies no policy itself.
 *
 * @param maxEntries  Maximum word entries to retain; oldest entries (lowest count) are
 *                    evicted when the table is full.
 */
class FrequencyCache(private val maxEntries: Int = MAX_ENTRIES) {

    private val counts: LinkedHashMap<String, Int> = LinkedHashMap(maxOf(1, maxEntries * 2), 0.75f, true)

    /**
     * Records that the user committed [word].
     *
     * The caller must have checked [FieldPolicy.learningEnabled] and
     * [FieldPolicy.persistAllowed] before invoking this method.
     */
    @Synchronized
    fun record(word: String) {
        if (word.isBlank()) return
        val lower = word.lowercase()
        counts[lower] = (counts[lower] ?: 0) + 1
        if (counts.size > maxEntries) {
            evictLeastUsed()
        }
    }

    /**
     * Returns a non-negative log₂ boost for [word] to be added to the decoder's
     * combined score.
     *
     * Returns 0.0 when the word has never been recorded.  The boost is capped at
     * [MAX_BOOST] to prevent over-weighting a word the user typed once in an unusual
     * context.
     */
    @Synchronized
    fun logBoost(word: String): Float {
        val count = counts[word.lowercase()] ?: return 0f
        // log₂(count + 1) normalised to a [0, MAX_BOOST] range.
        return minOf(log2(count.toFloat() + 1f), MAX_BOOST)
    }

    /**
     * Returns a copy of the current (word → count) table for serialisation.
     */
    @Synchronized
    fun snapshot(): Map<String, Int> = HashMap(counts)

    /**
     * Replaces the current table with [data].
     *
     * Used to restore a previously serialised session. Entries exceeding [maxEntries]
     * are trimmed by dropping the lowest-count words first.
     */
    @Synchronized
    fun loadFrom(data: Map<String, Int>) {
        counts.clear()
        // Sort descending by count so we retain the most-used words on trim.
        data.entries
            .sortedByDescending { it.value }
            .take(maxEntries)
            .forEach { (k, v) -> counts[k] = v }
    }

    /** Removes the entry with the smallest count to make room for a new word. */
    private fun evictLeastUsed() {
        val minKey = counts.minByOrNull { it.value }?.key ?: return
        counts.remove(minKey)
    }

    companion object {
        /** Default table capacity (words). */
        const val MAX_ENTRIES: Int = 4_096

        /** Maximum log₂ boost any single word can receive. */
        const val MAX_BOOST: Float = 5f

        /**
         * A no-op cache used when learning is disabled or in tests that don't need it.
         *
         * With maxEntries=0, every [record] call immediately evicts the inserted word,
         * so [logBoost] always returns 0.
         */
        val NOOP: FrequencyCache = FrequencyCache(maxEntries = 0)
    }
}
