package dev.tally.math

import java.math.BigDecimal
import java.util.Locale

/**
 * Suggestion returned when the engine detects and evaluates arithmetic near the cursor.
 *
 * @param display    Locale-formatted result string ready to show in the suggestion strip.
 * @param span       Byte range of the source expression in the original text buffer.
 * @param exactValue Full-precision result; retained for future "insert exact" option.
 */
data class Suggestion(
    val display: String,
    val span: IntRange,
    val exactValue: BigDecimal,
)

/**
 * Entry point for the arithmetic engine.
 *
 * Contract: pure, deterministic, total (never throws), bounded in time and memory.
 * No Android dependencies; no I/O; no globals.
 */
object MathEngine {
    /**
     * Given the text immediately before the cursor and a locale, returns a [Suggestion]
     * if a confident arithmetic expression is detected, or null otherwise.
     *
     * The default trigger requires a trailing `=`; expressions without it yield null.
     */
    @JvmStatic
    fun evaluate(
        textBeforeCursor: String,
        locale: Locale = Locale.getDefault(),
    ): Suggestion? = runCatching {
        evaluateInternal(textBeforeCursor, locale)
    }.getOrNull()

    private fun evaluateInternal(text: String, locale: Locale): Suggestion? {
        val detected = Detector(locale).detect(text) ?: return null

        val tokens = Lexer(locale).tokenize(detected.text) ?: return null
        val ast = Parser(tokens).parse() ?: return null
        if (!ast.hasBinaryOp()) return null

        val result = Evaluator().evaluate(ast) ?: return null

        val maxInputScale = tokens.filterIsInstance<Token.Num>().maxOfOrNull { it.originalScale } ?: 0
        val display = Formatter(locale).format(result, maxInputScale)

        return Suggestion(display, detected.range, result)
    }
}
