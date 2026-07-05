package dev.tally.mathtext

import dev.tally.math.MathEngine
import dev.tally.math.PercentMode
import java.util.Locale

/**
 * Pure, Android-free bridge from a selected text fragment to a display-ready result string.
 *
 * This is the single piece of evaluation logic in the module and is deliberately free of any
 * Android type so it can be unit-tested on the JVM without Robolectric. It delegates all real
 * arithmetic to [MathEngine.evaluate] — the engine is not reimplemented here.
 *
 * Contract mirrors [MathEngine]: total (never throws) and deterministic. Any input that is not a
 * confident arithmetic expression yields `null`, which callers translate into a friendly,
 * no-op message.
 */
object ExpressionEvaluator {

    /**
     * Evaluates [input] (a raw text selection) and returns the locale-formatted result, or `null`
     * if [input] is blank or is not an expression the engine can confidently evaluate.
     *
     * The engine's [MathEngine.evaluate] requires a trailing `=` to trigger (it is designed for
     * the keyboard's just-typed-equals moment). A PROCESS_TEXT selection rarely includes one, so
     * this helper appends a single `=` when the trimmed input does not already end with one. We
     * never append a second `=` and never alter the user's operators or operands.
     *
     * @param input     the selected text; may be null, blank, prose, or a valid expression.
     * @param locale    locale used for decimal/grouping separators in the formatted result.
     * @param percentMode how `a + b%` style expressions are interpreted (default ADDITIVE).
     * @param precision decimal places in the result; -1 (default) = automatic.
     * @return the formatted result string (e.g. "125,000"), or null when nothing can be computed.
     */
    fun evaluateToDisplayString(
        input: String?,
        locale: Locale = Locale.getDefault(),
        percentMode: PercentMode = PercentMode.ADDITIVE,
        precision: Int = -1,
    ): String? {
        // Guard: nothing selected, or whitespace only.
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        // The detector triggers only on a trailing '='. Append one if the user's selection
        // lacks it; leave an existing trailing '=' untouched so "2+2=" is not turned into "2+2==".
        val triggerText = if (trimmed.endsWith('=')) trimmed else "$trimmed="

        // MathEngine.evaluate is pure and total — it returns null on parse errors, division by
        // zero, non-arithmetic text, or budget-exceeded, and never throws on hostile input.
        return MathEngine.evaluate(
            textBeforeCursor = triggerText,
            locale = locale,
            percentMode = percentMode,
            precision = precision,
        )?.display
    }
}
