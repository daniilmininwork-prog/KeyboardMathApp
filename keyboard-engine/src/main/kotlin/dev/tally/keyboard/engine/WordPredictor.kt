package dev.tally.keyboard.engine

/**
 * Produces word-completion and next-word [Suggestion]s from the current context.
 *
 * This interface is the decoder seam in `keyboard-engine`: pure JVM, no Android
 * imports. Implementations live in the `prediction` module and are wired into the
 * IME via dependency injection.
 *
 * A single decoder implementation covers all three output categories (autocorrect,
 * word completion, next-word) because they share the same spatial scorer and
 * language model — only the candidate selection step differs.
 */
interface WordPredictor : SuggestionSource {
    /**
     * Returns up to [maxResults] suggestions for the composing word in [ctx].
     *
     * Kinds returned may include [SuggestionKind.PREDICTION] (completions of the
     * current partial word) and [SuggestionKind.NEXT_WORD] (predictions for the
     * next word when [ctx.composingWord] is empty).
     *
     * [policy.suggestionsEnabled] must be checked by implementations; return
     * an empty list immediately when it is false.
     */
    fun predict(ctx: EditingContext, policy: FieldPolicy, maxResults: Int = 3): List<Suggestion>
}
