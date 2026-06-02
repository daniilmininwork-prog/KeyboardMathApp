package dev.tally.overlay

import dev.tally.glue.MathEvaluator

/**
 * Extracts the text slice that the engine should evaluate: up to [MathEvaluator.MAX_TEXT_LENGTH]
 * characters ending at the cursor. Keeping only what precedes the cursor means the engine
 * never sees content the user has not yet typed past, and limits the read scope to the minimum
 * needed to find an expression.
 */
internal object TextExtractor {

    fun extractBeforeCursor(
        text: String,
        cursorEnd: Int,
        maxLength: Int = MathEvaluator.MAX_TEXT_LENGTH,
    ): String {
        if (text.isEmpty() || cursorEnd <= 0) return ""
        val end = cursorEnd.coerceAtMost(text.length)
        val start = (end - maxLength).coerceAtLeast(0)
        return text.substring(start, end)
    }
}
