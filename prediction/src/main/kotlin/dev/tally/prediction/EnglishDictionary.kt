package dev.tally.prediction

/**
 * In-memory representation of the English lexicon and unigram/bigram language model.
 *
 * Produced by [DictionaryLoader] after a background-thread load of the bundled
 * asset.  All mutation happens in the loader; this class is read-only once built.
 *
 * @param words  Sorted word list. Index corresponds to bigram references.
 * @param unigramLogProbs  Smoothed unigram log-probabilities (log base 2), same
 *   order as [words].  Values are negative; higher (less negative) is more probable.
 * @param bigramW1  Parallel array: first word index for bigram i.
 * @param bigramW2  Parallel array: second word index for bigram i.
 * @param bigramLogProbs  Smoothed bigram log-probabilities (log base 2) for bigram i.
 */
class EnglishDictionary(
    val words: Array<String>,
    val unigramLogProbs: FloatArray,
    val bigramW1: IntArray,
    val bigramW2: IntArray,
    val bigramLogProbs: FloatArray,
) {
    /** Number of words in the lexicon. */
    val wordCount: Int get() = words.size

    /** Number of bigram entries in the language model. */
    val bigramCount: Int get() = bigramW1.size

    /**
     * Returns the index of [word] in the sorted word array, or -1 if absent.
     *
     * Binary search — O(log N).
     */
    fun indexOf(word: String): Int {
        val i = words.binarySearch(word)
        return if (i >= 0) i else -1
    }

    /**
     * Returns the smoothed unigram log-probability (log₂) for [word], or a
     * back-off floor value if the word is not in the lexicon.
     */
    fun unigramLogProb(word: String): Float {
        val idx = indexOf(word)
        return if (idx >= 0) unigramLogProbs[idx] else UNSEEN_LOG_PROB
    }

    /**
     * Returns the smoothed bigram log-probability (log₂) for the pair
     * ([prev], [word]).  Falls back to the unigram probability when the
     * bigram is not in the model.
     */
    fun bigramLogProb(prev: String, word: String): Float {
        val w1 = indexOf(prev)
        val w2 = indexOf(word)
        if (w1 < 0 || w2 < 0) return unigramLogProb(word)
        // Bigrams are stored sorted by (w1, w2) so we can binary-search.
        val idx = findBigram(w1, w2)
        return if (idx >= 0) bigramLogProbs[idx] else unigramLogProb(word)
    }

    private fun findBigram(w1: Int, w2: Int): Int {
        var lo = 0
        var hi = bigramW1.size - 1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val cmp = bigramW1[mid].compareTo(w1).takeIf { it != 0 }
                ?: bigramW2[mid].compareTo(w2)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else    -> return mid
            }
        }
        return -1
    }

    companion object {
        /**
         * Log₂ probability floor for unseen words and missing bigram back-off.
         * Corresponds roughly to a word appearing once in a billion-word corpus.
         */
        const val UNSEEN_LOG_PROB: Float = -30f
    }
}
