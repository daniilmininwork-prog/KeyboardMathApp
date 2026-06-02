package dev.tally.prediction

/**
 * Wire format constants for the Tally binary dictionary asset (.tally-dict).
 *
 * The format is designed for sequential load into memory: the header positions
 * the sections, words are length-prefixed UTF-8, and bigrams are stored as
 * compact triplets referencing word indices. Both sections are sorted so a
 * future in-place reader can binary-search without deserializing everything.
 *
 * Layout (all integers big-endian):
 *
 *   [0..9]   magic:        ASCII "TALLY_DICT"
 *   [10..11] version:      0x0001
 *   [12..15] wordCount:    number of word entries (N)
 *   [16..19] bigramCount:  number of bigram entries (M)
 *   [20..]   words:        N × (1-byte length, UTF-8 bytes, 4-byte frequency)
 *   [..]     bigrams:      M × (3-byte w1Index, 3-byte w2Index, 4-byte logProbF32)
 *
 * Word frequencies are raw corpus counts; the loader normalises to unigram
 * log-probabilities with Kneser-Ney discounting at runtime so the format stays
 * independent of the smoothing decision.
 *
 * Bigram log-probabilities are stored as IEEE 754 single-precision floats
 * (log base 2) — precise enough for beam search rescoring.
 *
 * Size contract (D17): the compiled English asset must stay below 5 MB.
 */
internal object DictionaryFormat {

    /** ASCII "TALLY_DICT" — 10 bytes. */
    val MAGIC: ByteArray = "TALLY_DICT".toByteArray(Charsets.US_ASCII)

    /** Currently supported format version. */
    const val VERSION: Short = 1

    /** Maximum word byte-length representable in a single length byte. */
    const val MAX_WORD_BYTES: Int = 255

    /** Number of bytes in a bigram entry (w1: 3, w2: 3, logProb: 4). */
    const val BIGRAM_ENTRY_BYTES: Int = 10

    /** Offset of the word-count field in the header. */
    const val OFFSET_WORD_COUNT: Int = 12

    /** Offset of the bigram-count field in the header. */
    const val OFFSET_BIGRAM_COUNT: Int = 16

    /** Total fixed header length (magic + version + word count + bigram count). */
    const val HEADER_BYTES: Int = 20
}
