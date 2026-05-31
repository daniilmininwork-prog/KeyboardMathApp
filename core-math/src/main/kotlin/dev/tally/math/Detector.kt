package dev.tally.math

import java.util.Locale

internal class Detector(private val locale: Locale) {
    companion object {
        private const val MAX_SPAN = 256
    }

    data class DetectedSpan(val text: String, val range: IntRange)

    fun detect(textBeforeCursor: String): DetectedSpan? {
        // Require a trailing = (possibly with leading spaces before it)
        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty() || trimmed.last() != '=') return null

        val beforeEq = trimmed.dropLast(1)

        // Limit the look-back window
        val scanStart = maxOf(0, beforeEq.length - MAX_SPAN)
        val window = beforeEq.substring(scanStart)

        val (relRange, text) = findArithmeticFragment(window) ?: return null

        val absStart = scanStart + relRange.first
        val absEnd = scanStart + relRange.last

        // Veto: check expression text
        if (Vetoes.isVetoed(text)) return null

        // Veto: identifier-adjacent in the original buffer
        if (Vetoes.isIdentifierAdjacent(textBeforeCursor, absStart, absEnd + 1)) return null

        return DetectedSpan(text, absStart..absEnd)
    }

    private fun findArithmeticFragment(text: String): Pair<IntRange, String>? {
        val wordRanges = wordRanges(text)
        if (wordRanges.isEmpty()) return null

        val lastEnd = wordRanges.last().last

        for (startIdx in wordRanges.indices) {
            val startPos = wordRanges[startIdx].first
            val candidate = text.substring(startPos, lastEnd + 1)
            if (candidate.length > MAX_SPAN) continue

            val tokens = Lexer(locale).tokenize(candidate) ?: continue
            if (!hasRequiredStructure(tokens)) continue
            val ast = Parser(tokens).parse() ?: continue
            if (!ast.hasBinaryOp()) continue

            return Pair(startPos..lastEnd, candidate)
        }
        return null
    }

    private fun wordRanges(text: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isWhitespace()) {
                val s = i
                while (i < text.length && !text[i].isWhitespace()) i++
                result += s until i
            } else i++
        }
        return result
    }

    private fun hasRequiredStructure(tokens: List<Token>): Boolean {
        val hasBinOp = tokens.any {
            it is Token.Plus || it is Token.Minus || it is Token.Times || it is Token.Divide
        }
        val numCount = tokens.count { it is Token.Num }
        return hasBinOp && numCount >= 2
    }
}
