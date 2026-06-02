package dev.tally.math

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
     *
     * @param percentMode  How additive-percent expressions like `100+20%` are evaluated.
     * @param precision    Decimal places to show in the result (>=0); -1 = automatic.
     */
    fun evaluate(
        textBeforeCursor: String,
        locale: Locale = defaultLocale(),
        percentMode: PercentMode = PercentMode.ADDITIVE,
        precision: Int = -1,
    ): Suggestion? {
        // Expected failures (parse errors, eval budget exceeded, division by zero, etc.) are
        // handled by Parser and Evaluator returning null via their own narrow catches.
        // ArithmeticException from BigDecimal platform actuals (e.g. non-terminating decimals
        // in exact mode) is caught here as a known-safe fallback.
        // All other Throwables propagate so programming errors remain visible during development.
        return try {
            evaluateInternal(textBeforeCursor, locale, percentMode, precision)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun evaluateInternal(
        text: String,
        locale: Locale,
        percentMode: PercentMode,
        precision: Int,
    ): Suggestion? {
        val detected = Detector(locale).detect(text) ?: return null

        val tokens = Lexer(locale).tokenize(detected.text) ?: return null
        val ast = Parser(tokens).parse() ?: return null
        if (!ast.hasBinaryOp()) return null

        // Arithmetic evaluation — pure tree walk, no dynamic code execution
        val result = Evaluator(percentMode).evaluate(ast) ?: return null

        val maxInputScale = tokens.filterIsInstance<Token.Num>().maxOfOrNull { it.originalScale } ?: 0
        val display = Formatter(locale).format(result, maxInputScale, overridePrecision = precision)

        return Suggestion(display, detected.range, result)
    }
}
