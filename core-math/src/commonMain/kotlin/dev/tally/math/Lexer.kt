package dev.tally.math

internal class Lexer(locale: Locale) {
    companion object {
        // Hard cap: numbers with more significant digits than this are rejected. The scan
        // window is 256 characters, so a single operand can have at most ~255 digits; this
        // cap is a belt-and-suspenders defense against pathological input with the window
        // already providing the outer bound. Chosen conservatively to keep BigDecimal
        // allocations predictable while allowing any plausible real-world number.
        internal const val MAX_SIGNIFICANT_DIGITS = 200
    }

    private val decimalSep = decimalSeparatorFor(locale)
    private val groupingSep = groupingSeparatorFor(locale)

    // Returns -1 if c is not a decimal digit in any script.
    private fun digitValue(c: Char): Int {
        if (!c.isDigit()) return -1
        val v = c.digitToIntOrNull() ?: return -1
        return if (v in 0..9) v else -1
    }

    fun tokenize(input: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0

        while (i < input.length) {
            val c = input[i]

            when {
                c == ' ' || c == '\t' || c == ' ' || c == ' ' -> i++

                c == '+' -> { tokens += Token.Plus; i++ }

                c == '-' || c == '−' -> { tokens += Token.Minus; i++ }

                c == '*' || c == '×' || c == '⋅' -> { tokens += Token.Times; i++ }

                c == '/' || c == '÷' -> { tokens += Token.Divide; i++ }

                // x/X between digits only; spaces between are fine
                (c == 'x' || c == 'X') -> {
                    val prevIsNum = tokens.lastOrNull() is Token.Num
                    var j = i + 1
                    while (j < input.length && (input[j] == ' ' || input[j] == '\t')) j++
                    val nextIsDigit = j < input.length && digitValue(input[j]) >= 0
                    if (prevIsNum && nextIsDigit) { tokens += Token.Times; i++ }
                    else return null
                }

                c == '(' -> { tokens += Token.LParen; i++ }
                c == ')' -> { tokens += Token.RParen; i++ }
                c == '%' -> { tokens += Token.Percent; i++ }

                digitValue(c) >= 0 || c == decimalSep -> {
                    val (tok, end) = readNumber(input, i) ?: return null
                    tokens += tok
                    i = end
                }

                else -> return null
            }
        }
        return tokens
    }

    private fun readNumber(input: String, start: Int): Pair<Token.Num, Int>? {
        val digits = StringBuilder()
        var i = start
        var hasDecimal = false

        // Leading decimal separator (e.g. ".5")
        if (i < input.length && input[i] == decimalSep) {
            val next = i + 1
            if (next >= input.length || digitValue(input[next]) < 0) return null
            digits.append('.')
            hasDecimal = true
            i++
        }

        while (i < input.length) {
            val c = input[i]
            val dv = digitValue(c)
            when {
                dv >= 0 -> { digits.append(dv.digitToChar()); i++ }
                c == groupingSep && !hasDecimal && i + 3 < input.length -> {
                    // Accept grouping separator only if the next three characters are all digits
                    val a = digitValue(input[i + 1])
                    val b = digitValue(input[i + 2])
                    val cc = digitValue(input[i + 3])
                    if (a >= 0 && b >= 0 && cc >= 0) i++ // skip sep; digits consumed next iteration
                    else break
                }
                c == decimalSep && !hasDecimal -> {
                    digits.append('.')
                    hasDecimal = true
                    i++
                }
                else -> break
            }
        }

        if (digits.isEmpty() || digits.toString() == ".") return null

        val raw = digits.toString()

        // Significant-digit cap: count digits excluding the leading decimal point.
        val sigDigits = raw.replace(".", "").length
        if (sigDigits > MAX_SIGNIFICANT_DIGITS) return null

        val bd = runCatching { bigDecimalOf(raw) }.getOrNull() ?: return null
        val scale = if (hasDecimal) raw.length - raw.indexOf('.') - 1 else 0
        return Pair(Token.Num(bd, scale), i)
    }
}
