package dev.tally.glue

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tally.math.MathEngine
import dev.tally.math.PercentMode
import dev.tally.math.Suggestion
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Bridges the keyboard host and [MathEngine].
 *
 * Debounces rapid text changes, evaluates off the UI thread, and delivers
 * results back on the main thread via [onResult].
 *
 * [onResult] is the last parameter so callers can use trailing-lambda syntax.
 */
class MathEvaluator(
    val debounceMs: Long = DEBOUNCE_MS,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val bgExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val onResult: (Suggestion?) -> Unit,
) {
    @Volatile private var pendingText: String = ""
    @Volatile private var pendingLocale: Locale = Locale.getDefault()
    @Volatile private var pendingPercentMode: PercentMode = PercentMode.ADDITIVE
    @Volatile private var pendingPrecision: Int = TallyPreferences.DEFAULT_PRECISION

    private val debounceRunnable = Runnable {
        val text = pendingText
        val locale = pendingLocale
        val percentMode = pendingPercentMode
        val precision = pendingPrecision
        bgExecutor.execute {
            // Wrap the entire background body so unexpected Throwables from MathEngine (or
            // programming errors in platform actuals) are not silently swallowed by the
            // Executor's default uncaught-exception handler, which on Android only logs to
            // stderr and is invisible in production. If MathEngine throws unexpectedly here,
            // the exception propagates to the Executor; this try-catch converts it to a null
            // result so the UI clears the chip rather than leaving a stale suggestion.
            val result = try {
                MathEngine.evaluate(text, locale, percentMode, precision)
            } catch (e: Throwable) {
                if (e is Error) {
                    // Real VM errors (OutOfMemoryError, StackOverflowError, etc.) must not be
                    // silently swallowed — rethrow so the executor's uncaught-exception handler
                    // and crash reporters see the real cause.
                    throw e
                }
                Log.e(TAG, "MathEngine.evaluate threw unexpectedly; clearing suggestion " +
                    "[text=${text.take(80)}, locale=$locale, precision=$precision]; " +
                    "truncated for privacy/size", e)
                null
            }
            mainHandler.post { onResult(result) }
        }
    }

    fun onTextChanged(
        text: String,
        locale: Locale = Locale.getDefault(),
        percentMode: PercentMode = PercentMode.ADDITIVE,
        precision: Int = TallyPreferences.DEFAULT_PRECISION,
    ) {
        pendingText = text
        pendingLocale = locale
        pendingPercentMode = percentMode
        pendingPrecision = precision
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, debounceMs)
    }

    fun cancel() {
        mainHandler.removeCallbacks(debounceRunnable)
    }

    companion object {
        const val DEBOUNCE_MS = 90L
        const val MAX_TEXT_LENGTH = 512
        private const val TAG = "MathEvaluator"
    }
}
