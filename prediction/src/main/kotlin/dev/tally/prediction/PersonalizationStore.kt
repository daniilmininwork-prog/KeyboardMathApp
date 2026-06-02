package dev.tally.prediction

import dev.tally.keyboard.engine.FieldPolicy

/**
 * Enforces [FieldPolicy] gates before writing to the in-memory [FrequencyCache].
 *
 * This is the single authoritative gate for all personalization writes. Callers must
 * pass the current [FieldPolicy] on every [record] call; the store silently discards
 * the observation when either [FieldPolicy.learningEnabled] or [FieldPolicy.persistAllowed]
 * is false.
 *
 * Placing the gate here — rather than at each call site — means instrumented tests and
 * any future persistence layer have exactly one place to audit. The [FrequencyCache]
 * itself remains policy-agnostic so it can be used in tests without a policy context.
 *
 * @param cache  The backing frequency table. Defaults to a normal [FrequencyCache];
 *               pass [FrequencyCache.NOOP] to disable personalisation for the whole session.
 */
class PersonalizationStore(
    internal val cache: FrequencyCache = FrequencyCache(),
) {

    /**
     * Records that the user committed [word] while [policy] was active.
     *
     * No-ops when [FieldPolicy.learningEnabled] or [FieldPolicy.persistAllowed] is false.
     * Both flags must be true for the observation to reach the backing [cache]; either
     * false is sufficient to suppress it.
     *
     * Thread-safe: delegates to the synchronized [FrequencyCache.record].
     */
    fun record(word: String, policy: FieldPolicy) {
        if (!policy.learningEnabled || !policy.persistAllowed) return
        cache.record(word)
    }

    /**
     * Returns a log₂ frequency boost for [word], derived from the cached observation
     * counts accumulated across permissive fields.
     *
     * The returned value is always non-negative; 0 means no observations recorded.
     */
    fun logBoost(word: String): Float = cache.logBoost(word)

    /**
     * Snapshot of the current (word → count) table for serialisation.
     *
     * Callers are responsible for encrypting and storing this snapshot; the store itself
     * is in-memory only and does not touch the file system.
     */
    fun snapshot(): Map<String, Int> = cache.snapshot()

    /**
     * Restores the store from a previously persisted snapshot.
     *
     * Entries exceeding the cache's [FrequencyCache.MAX_ENTRIES] are trimmed by the
     * backing [FrequencyCache.loadFrom] implementation.
     */
    fun loadFrom(data: Map<String, Int>) = cache.loadFrom(data)

    companion object {
        /**
         * A no-op store for use in secure/private sessions or tests.
         *
         * Every [record] call silently discards its argument; [logBoost] always returns 0.
         */
        val NOOP: PersonalizationStore = PersonalizationStore(FrequencyCache.NOOP)
    }
}
