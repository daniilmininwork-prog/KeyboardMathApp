package dev.tally.keyboard.engine

/**
 * A snapshot of the editor state as the decoder sees it.
 *
 * Populated from the IME's local mirror of the [InputConnection] — never from a
 * live IPC read during a suggestion query. Passed to [SuggestionSource.query] and
 * [WordPredictor.predict] so the decoder has everything it needs without touching
 * Android APIs.
 *
 * @param composingWord   The word currently under composition, or empty if none.
 *                        This is what the decoder uses for tap-autocorrect and
 *                        word completion.
 * @param textBeforeCursor Up to N characters of committed text before the cursor,
 *                         used for bigram context (the last committed word).
 * @param wordBeforeCursor The last fully committed word before the cursor, extracted
 *                         from [textBeforeCursor]. Null when the cursor is at the
 *                         start of a sentence or after a non-word character.
 */
data class EditingContext(
    val composingWord: String,
    val textBeforeCursor: String,
    val wordBeforeCursor: String?,
) {
    companion object {
        /** Empty context used before any input has been processed. */
        val EMPTY = EditingContext(
            composingWord      = "",
            textBeforeCursor   = "",
            wordBeforeCursor   = null,
        )
    }
}
