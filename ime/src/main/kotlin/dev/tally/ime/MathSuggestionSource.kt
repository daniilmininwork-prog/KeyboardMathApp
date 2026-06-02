package dev.tally.ime

import dev.tally.math.Suggestion as MathSuggestion

/**
 * Adapter that connects [dev.tally.glue.MathEvaluator] to the [StripCoordinator] pipeline.
 *
 * The evaluator runs on its own 90 ms-debounced background executor and posts results
 * to the main thread. This adapter receives those results, keeps the latest one, and
 * exposes a [commit] entry-point that the IME calls when the user taps the chip or
 * presses Space while a math result is showing.
 *
 * ## Commit semantics (03 §4.1)
 *
 *  The caller provides a [CommitAction] that receives the raw [MathSuggestion] — including
 *  [MathSuggestion.span] (for expression-replace) and [MathSuggestion.exactValue] (for
 *  full-precision insert). This keeps [MathSuggestionSource] free of [android.view.inputmethod.InputConnection]
 *  dependencies and testable on the JVM without a device.
 *
 * ## Threading
 *
 *  All methods must be called on the main thread (matching [dev.tally.glue.MathEvaluator]'s
 *  callback contract).
 */
internal class MathSuggestionSource {

    /**
     * Callback type for the commit action.
     *
     * Receives the raw [MathSuggestion] so the IME can perform span-replace and
     * exactValue substitution inside a batch edit using the live [InputConnection].
     */
    fun interface CommitAction {
        fun commit(math: MathSuggestion)
    }

    /** The most recent math result; null when the evaluator has nothing to show. */
    var latest: MathSuggestion? = null
        private set

    /** True when there is a math result that can be committed. */
    val hasResult: Boolean get() = latest != null

    /**
     * Receives a new result from [dev.tally.glue.MathEvaluator].
     *
     * Stores the result so [commit] can act on it. The caller is responsible for
     * forwarding [math] to [StripCoordinator.updateMath] so the chip reflects the
     * new state.
     */
    fun onResult(math: MathSuggestion?) {
        latest = math
    }

    /**
     * Commits the current [latest] result via [action] and clears the stored state.
     *
     * Calling commit when [hasResult] is false is a no-op; the [action] is not invoked.
     *
     * After commit [latest] is nulled to prevent a stale second tap from re-inserting.
     *
     * @return true if a result was present and committed, false if there was nothing to commit.
     */
    fun commit(action: CommitAction): Boolean {
        val math = latest ?: return false
        latest = null
        action.commit(math)
        return true
    }

    /** Discards the pending result without committing. */
    fun clear() {
        latest = null
    }
}
