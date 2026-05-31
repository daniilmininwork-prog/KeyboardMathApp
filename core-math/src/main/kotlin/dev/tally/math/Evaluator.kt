package dev.tally.math

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal class Evaluator {
    companion object {
        private val MC = MathContext(34, RoundingMode.HALF_EVEN)
        private val HUNDRED = BigDecimal("100")
        private const val STEP_BUDGET = 10_000
        private val MAX_MAGNITUDE = BigDecimal("1E9999")
    }

    private class EvalFail : Throwable()

    private var steps = 0

    fun evaluate(expr: Expr): BigDecimal? {
        steps = 0
        return runCatching { eval(expr) }.getOrNull()
    }

    private fun eval(expr: Expr): BigDecimal {
        if (++steps > STEP_BUDGET) throw EvalFail()
        return when (expr) {
            is NumExpr -> expr.value

            is UnaryExpr -> {
                val v = eval(expr.operand)
                if (expr.negate) v.negate() else v
            }

            is PercentExpr -> eval(expr.operand).divide(HUNDRED, MC)

            is BinaryExpr -> evalBinary(expr)
        }
    }

    private fun evalBinary(expr: BinaryExpr): BigDecimal {
        // Additive-percent shortcut: `a + b%` and `a - b%`
        if ((expr.op == BinOp.ADD || expr.op == BinOp.SUB) && expr.right is PercentExpr) {
            val base = eval(expr.left)
            val pct = eval((expr.right as PercentExpr).operand)
            val delta = base.multiply(pct, MC).divide(HUNDRED, MC)
            return if (expr.op == BinOp.ADD) base.add(delta, MC) else base.subtract(delta, MC)
        }

        val l = eval(expr.left)
        val r = eval(expr.right)

        val result = when (expr.op) {
            BinOp.ADD -> l.add(r, MC)
            BinOp.SUB -> l.subtract(r, MC)
            BinOp.MUL -> l.multiply(r, MC)
            BinOp.DIV -> {
                if (r.compareTo(BigDecimal.ZERO) == 0) throw EvalFail()
                l.divide(r, MC)
            }
        }

        if (result.abs().compareTo(MAX_MAGNITUDE) > 0) throw EvalFail()
        return result
    }
}
