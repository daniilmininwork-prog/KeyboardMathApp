package dev.tally.math

import java.math.BigDecimal

internal sealed interface Expr {
    fun hasBinaryOp(): Boolean
}

internal data class NumExpr(val value: BigDecimal) : Expr {
    override fun hasBinaryOp() = false
}

internal enum class BinOp { ADD, SUB, MUL, DIV }

internal data class BinaryExpr(val left: Expr, val op: BinOp, val right: Expr) : Expr {
    override fun hasBinaryOp() = true
}

internal data class UnaryExpr(val negate: Boolean, val operand: Expr) : Expr {
    override fun hasBinaryOp() = operand.hasBinaryOp()
}

internal data class PercentExpr(val operand: Expr) : Expr {
    override fun hasBinaryOp() = operand.hasBinaryOp()
}
