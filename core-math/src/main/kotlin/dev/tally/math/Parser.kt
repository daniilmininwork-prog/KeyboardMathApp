package dev.tally.math

internal class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private var depth = 0

    companion object {
        private const val MAX_DEPTH = 32
    }

    private class ParseFail : Throwable()

    fun parse(): Expr? = runCatching {
        if (tokens.isEmpty()) throw ParseFail()
        val expr = parseExpression()
        if (pos != tokens.size) throw ParseFail()
        expr
    }.getOrNull()

    private fun parseExpression(): Expr {
        var left = parseTerm()
        while (pos < tokens.size) {
            val op = when (tokens[pos]) {
                Token.Plus -> BinOp.ADD
                Token.Minus -> BinOp.SUB
                else -> break
            }
            pos++
            left = BinaryExpr(left, op, parseTerm())
        }
        return left
    }

    private fun parseTerm(): Expr {
        var left = parseFactor()
        while (pos < tokens.size) {
            val op = when (tokens[pos]) {
                Token.Times -> BinOp.MUL
                Token.Divide -> BinOp.DIV
                else -> break
            }
            pos++
            left = BinaryExpr(left, op, parseFactor())
        }
        return left
    }

    private fun parseFactor(): Expr {
        val operand = parseUnary()
        return if (pos < tokens.size && tokens[pos] == Token.Percent) {
            pos++
            PercentExpr(operand)
        } else operand
    }

    private fun parseUnary(): Expr {
        return when {
            pos < tokens.size && tokens[pos] == Token.Plus -> { pos++; parseUnary() }
            pos < tokens.size && tokens[pos] == Token.Minus -> { pos++; UnaryExpr(true, parseUnary()) }
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Expr {
        if (pos >= tokens.size) throw ParseFail()
        return when (val tok = tokens[pos]) {
            is Token.Num -> { pos++; NumExpr(tok.value) }
            Token.LParen -> {
                if (++depth > MAX_DEPTH) throw ParseFail()
                pos++
                val expr = parseExpression()
                if (pos >= tokens.size || tokens[pos] != Token.RParen) throw ParseFail()
                pos++
                depth--
                expr
            }
            else -> throw ParseFail()
        }
    }
}
