package dev.tally.keyboard.engine

/**
 * Decides whether the current composing word should be silently corrected on commit.
 *
 * This interface is the autocorrect seam in `keyboard-engine`: pure JVM, no Android
 * imports. Implementations live in the `prediction` module.
 *
 * Autocorrect fires when the user presses Space, Enter, or punctuation to commit a
 * composing word. The IME calls [correct] and, if a non-null [Suggestion] is returned,
 * replaces the composing region with that text via a batch edit before committing.
 */
interface Autocorrector : SuggestionSource {
    /**
     * Returns a correction [Suggestion] if [ctx.composingWord] should be replaced,
     * or null when the typed word is acceptable as-is.
     *
     * Implementations should apply a conservative confidence threshold — a false
     * autocorrect is more disruptive than a missed one. High-frequency in-lexicon
     * words are not corrected even if a closer candidate exists.
     *
     * [policy.suggestionsEnabled] must be checked; return null immediately when false.
     */
    fun correct(ctx: EditingContext, policy: FieldPolicy): Suggestion?
}
