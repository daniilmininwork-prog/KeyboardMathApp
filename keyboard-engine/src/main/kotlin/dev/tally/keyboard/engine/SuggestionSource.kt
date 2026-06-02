package dev.tally.keyboard.engine

/**
 * Produces [Suggestion]s for the current editing context.
 *
 * Implementations run on a background executor — they must never touch the View
 * tree or the [android.view.inputmethod.InputConnection] directly. Results are
 * posted to the strip on the main thread by the IME coordinator.
 *
 * Multiple sources are composed by the strip coordinator; each source is
 * responsible only for its own [SuggestionKind].
 */
fun interface SuggestionSource {
    /**
     * Returns candidates for [ctx] under the constraints in [policy].
     *
     * Must return an empty list (not throw) when [policy.suggestionsEnabled] is
     * false or when the source has nothing relevant.  Implementations should be
     * bounded in runtime — the strip coalesces to ≤ 1 update per input event and
     * drops stale results from superseded calls.
     */
    fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion>
}
