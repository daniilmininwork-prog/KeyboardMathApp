package dev.tally.math

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
        if (Vetoes.isVetoed(text, locale)) return null

        // Veto: identifier-adjacent in the original buffer
        if (Vetoes.isIdentifierAdjacent(textBeforeCursor, absStart, absEnd + 1)) return null

        return DetectedSpan(text, absStart..absEnd)
    }

    private fun findArithmeticFragment(text: String): Pair<IntRange, String>? {
        val words = wordRanges(text)
        if (words.isEmpty()) return null

        // Two-dimensional search: vary both the right and left word boundaries.
        // Right-anchoring: the expression must end at the rightmost word that is
        // part of arithmetic — any purely-prose trailing words are dropped. Once a
        // right endpoint is chosen, we verify that no arithmetic word lies further right
        // (which would mean the expression is embedded mid-text rather than right-anchored).
        for (endIdx in words.indices.reversed()) {
            val endWord = text.substring(words[endIdx])
            if (!isArithmeticWord(endWord)) continue   // drop non-arithmetic trailing words

            // Ensure no arithmetic word sits to the right of this candidate end.
            val hasArithmeticAfter = (endIdx + 1 until words.size).any { j ->
                isArithmeticWord(text.substring(words[j]))
            }
            if (hasArithmeticAfter) continue

            val endPos = words[endIdx].last

            for (startIdx in endIdx downTo 0) {
                val startPos = words[startIdx].first
                val candidate = text.substring(startPos, endPos + 1)
                if (candidate.length > MAX_SPAN) continue

                val tokens = Lexer(locale).tokenize(candidate) ?: continue
                if (!hasRequiredStructure(tokens)) continue
                val ast = Parser(tokens).parse() ?: continue
                if (!ast.hasBinaryOp()) continue

                return Pair(startPos..endPos, candidate)
            }
        }
        return null
    }

    // A word is "arithmetic" if the lexer can extract at least one valid token from it.
    // Used to distinguish prose words (which fail tokenization entirely) from arithmetic content.
    private fun isArithmeticWord(word: String): Boolean {
        val toks = Lexer(locale).tokenize(word)
        return toks != null && toks.isNotEmpty()
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
