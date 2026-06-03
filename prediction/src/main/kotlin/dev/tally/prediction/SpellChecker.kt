package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext

/**
 * On-device spell-checker driven by the bundled [EnglishDictionary] (Stage 5).
 *
 * A word is "misspelled" when it is absent from the lexicon. This is deliberately the
 * same lookup the rest of the prediction stack already uses ([EnglishDictionary.indexOf]),
 * so there is no second dictionary to bundle, load, or keep in sync — the red-underline
 * surface and the autocorrect surface agree on what "a real word" is.
 *
 * Everything here is pure JVM with no Android dependency so it can be unit-tested without
 * an emulator and so the hard "no-network" rule holds by construction: the only data source
 * is the in-memory dictionary that was loaded from the APK asset.
 *
 * Corrections reuse the shared [BeamDecoder] (the same instance backing word prediction and
 * autocorrect-on-space) so the suggestions offered for a red-underlined word match what the
 * keyboard would have corrected to anyway.
 *
 * @param dictionary  The loaded lexicon; the sole source of truth for "known" words.
 * @param decoder     Beam-search decoder shared with [WordPredictorImpl] / [AutocorrectorImpl],
 *                    used to rank correction candidates for an unknown word.
 */
class SpellChecker internal constructor(
    private val dictionary: EnglishDictionary,
    private val decoder: BeamDecoder,
) {

    /**
     * Returns true when [word] should be flagged with a red spell-check underline.
     *
     * A word is flagged only when it is a plain alphabetic run of at least
     * [MIN_CHECK_LENGTH] code points that is absent from the dictionary. The guards exist so
     * the keyboard never underlines things that are not ordinary prose words:
     *
     *  - **Too short** (length < [MIN_CHECK_LENGTH]): one- and two-letter tokens ("a", "ok",
     *    "hi", initials) are too noisy to flag and are usually intentional.
     *  - **Contains non-letters**: numbers, "v2", "café-au-lait" fragments, URLs, code, emoji —
     *    these are out of scope for a word lexicon and flagging them would be wrong far more
     *    often than right.
     *
     * The lookup is case-insensitive: "Hello" and "HELLO" are known if "hello" is in the
     * lexicon, so sentence-initial capitals and shouting are not falsely flagged.
     */
    fun isMisspelled(word: String): Boolean {
        if (word.length < MIN_CHECK_LENGTH) return false
        // Only check pure alphabetic words; a word lexicon has nothing useful to say about
        // tokens that mix in digits, punctuation, or symbols.
        if (!word.all { it.isLetter() }) return false
        return dictionary.indexOf(word.lowercase()) < 0
    }

    /**
     * Returns up to [maxResults] dictionary corrections for the (presumed misspelled) [word],
     * best first, for display in the suggestion strip when the user taps a red-underlined word.
     *
     * Delegates to the shared [BeamDecoder] so the ranking matches the rest of the prediction
     * stack. The typed word itself is filtered out (it is the thing being corrected) and the
     * results are de-duplicated case-insensitively. Geometry is null here because a tap-to-correct
     * request has no live touch path to score against — only edit-distance / prefix similarity.
     */
    fun corrections(word: String, maxResults: Int = DEFAULT_CORRECTIONS): List<String> {
        if (word.isEmpty()) return emptyList()
        val ctx = EditingContext(
            composingWord    = word,
            textBeforeCursor = word,
            wordBeforeCursor = null,
        )
        val seen = HashSet<String>()
        return decoder.decode(word, ctx, geometry = null, maxResults = maxResults + 1)
            .asSequence()
            .map { it.first }
            .filter { !it.equals(word, ignoreCase = true) }
            .filter { seen.add(it.lowercase()) }
            .take(maxResults)
            .toList()
    }

    companion object {
        /**
         * Minimum word length before the spell-checker flags an unknown word. Below this the
         * false-positive rate (initials, interjections, abbreviations) outweighs the value.
         */
        const val MIN_CHECK_LENGTH: Int = 3

        /** Default number of correction candidates offered on tap. */
        const val DEFAULT_CORRECTIONS: Int = 3
    }
}
