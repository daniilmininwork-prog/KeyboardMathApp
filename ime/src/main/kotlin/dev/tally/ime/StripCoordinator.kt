package dev.tally.ime

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.keyboard.engine.SuggestionSource
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/**
 * Queries multiple [SuggestionSource]s and merges their results into a [StripState].
 *
 * ## Strip composition rules (03 §4.1)
 *
 *  - **Math slot (leading):** a MATH candidate occupies the leading, visually-distinct position
 *    and is never displaced by word candidates. When the math source returns nothing the math slot
 *    is empty but word slots may still fill the strip.
 *  - **Candidate slots (trailing):** AUTOCORRECT, PREDICTION, and NEXT_WORD suggestions fill up
 *    to [MAX_WORD_SLOTS] slots, ranked by score descending. AUTOCORRECT takes priority over
 *    PREDICTION/NEXT_WORD within the same query.
 *  - **Coalescing:** rapid input events cancel the in-flight query before submitting a new one,
 *    so the strip receives ≤ 1 update per input event. Updates are posted to the main thread.
 *
 * @param wordSource    Initial word-suggestion source. Can be replaced via [setWordSource]
 *                      when the prediction dictionary finishes loading asynchronously.
 * @param bgExecutor    Background executor used for all source queries (never the UI thread).
 * @param mainHandler   Handler targeting the main thread; results are posted here.
 * @param onUpdate      Callback invoked on the main thread with the new [StripState].
 */
internal class StripCoordinator(
    wordSource: SuggestionSource,
    private val bgExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onUpdate: (StripState) -> Unit,
) {

    @Volatile private var wordSource: SuggestionSource = wordSource

    // Monotonically increasing sequence number; only the result from the latest submission
    // is delivered — earlier callbacks discard themselves by checking this value.
    private val sequence = AtomicLong(0L)

    // Tracked so we can interrupt a running query on the next submit.
    @Volatile private var inflight: FutureTask<*>? = null

    /**
     * Current math suggestion, set from the MathEvaluator callback on the main thread.
     *
     * Stored here so that every new word-query result incorporates the latest math state,
     * and so that a new math result can be merged with the existing word candidates without
     * re-running the decoder.
     */
    @Volatile private var latestMath: Suggestion? = null

    /**
     * Last successfully computed word candidates.
     *
     * Used to avoid blanking the strip when a new math result arrives between two word queries.
     */
    @Volatile private var latestWordCandidates: List<Suggestion> = emptyList()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Replaces the active word source.
     *
     * Safe to call from any thread. The replacement takes effect on the next
     * [requestUpdate] invocation; in-flight queries from the old source are cancelled.
     */
    fun setWordSource(source: SuggestionSource) {
        wordSource = source
    }

    /**
     * Queues a new suggestion query for [ctx] under [policy].
     *
     * Any in-flight query for a previous event is cancelled. The new query runs on
     * [bgExecutor]; the result arrives on the main thread via [onUpdate].
     *
     * Safe to call on the main thread; does not block.
     */
    fun requestUpdate(ctx: EditingContext, policy: FieldPolicy) {
        val seq = sequence.incrementAndGet()
        inflight?.cancel(true)
        val source = wordSource
        val task = FutureTask<Unit>({
            if (Thread.interrupted()) return@FutureTask
            val words = try {
                source.query(ctx, policy)
            } catch (_: InterruptedException) {
                return@FutureTask
            } catch (e: RuntimeException) {
                // Catch only RuntimeException (and its subclasses) from the word source.
                // This covers expected operational failures from BeamDecoder/WordPredictorImpl
                // (e.g. IllegalStateException if the dictionary is partially torn down) without
                // silently discarding NullPointerExceptions or other programming errors, which
                // would hide bugs in SuggestionSource implementations.
                // Note: InterruptedException is already handled above, so it will not reach here.
                Log.e(TAG, "Word source query failed; returning empty candidate list", e)
                emptyList()
            }
            if (sequence.get() != seq) return@FutureTask
            mainHandler.post {
                if (sequence.get() != seq) return@post
                latestWordCandidates = words
                onUpdate(compose(latestMath, words))
            }
        }, Unit)
        inflight = task
        bgExecutor.execute(task)
    }

    /**
     * Delivers a new math result from the [MathEvaluator] callback.
     *
     * Called on the main thread. Updates the strip immediately — no bg query needed since
     * the math chip is independent of the word candidates already in [latestWordCandidates].
     *
     * @param math null when the math source has nothing to show.
     */
    fun updateMath(math: dev.tally.math.Suggestion?) {
        latestMath = math?.let {
            Suggestion(
                kind  = SuggestionKind.MATH,
                text  = it.display,
                score = 0f,
            )
        }
        onUpdate(compose(latestMath, latestWordCandidates))
    }

    /**
     * Clears all state and notifies the strip with an empty [StripState].
     *
     * Called when the field changes or the IME session ends. Posts the empty state on the
     * main thread to avoid a race where an in-flight result arrives after the clear.
     */
    fun clear() {
        sequence.incrementAndGet()
        inflight?.cancel(true)
        inflight = null
        latestMath = null
        latestWordCandidates = emptyList()
        mainHandler.post { onUpdate(StripState.EMPTY) }
    }

    // ── Composition ───────────────────────────────────────────────────────────

    private fun compose(math: Suggestion?, words: List<Suggestion>): StripState {
        // AUTOCORRECT candidates take priority over PREDICTION/NEXT_WORD;
        // within the same kind, rank by score descending.
        val ranked = words
            .sortedWith(
                compareByDescending<Suggestion> { it.kind == SuggestionKind.AUTOCORRECT }
                    .thenByDescending { it.score }
            )
            .take(MAX_WORD_SLOTS)

        // Surface the leading AUTOCORRECT candidate separately so the controller can apply it
        // on Space without re-running the decoder. Ranking already places AUTOCORRECT first, so
        // the head of [ranked] is the candidate when one exists.
        val topAutocorrect = ranked.firstOrNull { it.kind == SuggestionKind.AUTOCORRECT }

        return StripState(
            mathSuggestion = math,
            wordCandidates = ranked,
            topAutocorrect = topAutocorrect,
        )
    }

    companion object {
        /** Maximum word/autocorrect slots shown alongside or in place of the math chip. */
        const val MAX_WORD_SLOTS = 3
        private const val TAG = "StripCoordinator"
    }
}

/**
 * Snapshot of what the strip should show at a given moment.
 *
 * @param mathSuggestion  MATH candidate for the leading reserved slot; null = slot empty.
 * @param wordCandidates  Up to [StripCoordinator.MAX_WORD_SLOTS] word/autocorrect candidates.
 * @param topAutocorrect  The leading AUTOCORRECT candidate (if any) extracted from
 *                        [wordCandidates]. Carried alongside the rendered list so the
 *                        controller can apply autocorrect-on-space using the candidate's
 *                        calibrated [Suggestion.confidence] without re-querying the decoder.
 */
internal data class StripState(
    val mathSuggestion: Suggestion?,
    val wordCandidates: List<Suggestion>,
    val topAutocorrect: Suggestion? = null,
) {
    companion object {
        val EMPTY = StripState(mathSuggestion = null, wordCandidates = emptyList())
    }
}
