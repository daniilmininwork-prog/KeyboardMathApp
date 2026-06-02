package dev.tally.keyboard.engine

/**
 * Decodes a continuous swipe trace into a ranked list of word candidates.
 *
 * This is the glide-typing seam in `keyboard-engine`: pure JVM, no Android imports.
 * The implementation lives in the `prediction` module ([dev.tally.prediction.GestureDecoderImpl])
 * and reuses the same lexicon and language model as the tap decoder, in line with the
 * design principle in `04 §2.1`: glide is a second traversal of the same engine.
 *
 * The algorithm is a statistical classifier ported from FlorisBoard (Apache-2.0):
 * resample the raw trace to a fixed number of points, compute per-word spatial scores
 * by comparing the resampled trace against each word's ideal key-path, and rerank with
 * the n-gram language model.
 *
 * **Patent note (per T4.1):** gesture typing methods are a patent-dense space. The
 * statistical approach implemented here (resample → DTW-adjacent scoring against ideal
 * key paths → LM rerank) is derived from the FlorisBoard open-source implementation
 * (Apache-2.0) and does not implement any of the patented continuous-speech-recognition
 * methods described in Google's AGDSL patent family. Legal sign-off should be obtained
 * before shipping this feature publicly; see `docs/adr/` for the tracking ADR.
 */
interface GestureDecoder {

    /**
     * Returns up to [maxResults] word candidates for the given swipe [path].
     *
     * [geometry] provides the key coordinates used to build ideal key-paths for scoring.
     * [context] supplies the preceding word for bigram rescoring.
     * [policy] is checked on entry: an empty list is returned immediately when
     * [FieldPolicy.glideEnabled] is false.
     *
     * The call may be long-running (lexicon scan); callers should invoke it on a
     * background executor and check [isActive] periodically. When [isActive] returns
     * false the decoder must return whatever candidates it has accumulated so far,
     * rather than throwing or blocking.
     *
     * Results are sorted by combined score descending.
     */
    fun decode(
        path: GlidePath,
        geometry: KeyGeometry,
        context: EditingContext,
        policy: FieldPolicy,
        maxResults: Int = 3,
        isActive: () -> Boolean = { true },
    ): List<Suggestion>
}
