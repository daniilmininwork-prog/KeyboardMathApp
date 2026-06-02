package dev.tally.math

internal class Evaluator(private val percentMode: PercentMode = PercentMode.ADDITIVE) {
    companion object {
        private val MC = mathContextOf(34, RoundingMode.HALF_EVEN)
        private val HUNDRED = BigDecimalHundred
        private const val STEP_BUDGET = 10_000
        private val MAX_MAGNITUDE = bigDecimalOf("1E9999")
    }

    private class EvalFail : Throwable()

    private var steps = 0

    fun evaluate(expr: Expr): BigDecimal? {
        steps = 0
        // Narrow the catch to EvalFail (budget overrun, division by zero, overflow) and
        // ArithmeticException (BigDecimal platform actuals in exact mode). All other
        // Throwables propagate so unexpected bugs remain visible.
        return try {
            eval(expr)
        } catch (_: EvalFail) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun eval(expr: Expr): BigDecimal {
        if (++steps > STEP_BUDGET) throw EvalFail()
        return when (expr) {
            is NumExpr -> expr.value

            is UnaryExpr -> {
                val v = eval(expr.operand)
                if (expr.negate) v.bdNegate() else v
            }

            is PercentExpr -> eval(expr.operand).bdDivide(HUNDRED, MC)

            is BinaryExpr -> evalBinary(expr)
        }
    }

    private fun evalBinary(expr: BinaryExpr): BigDecimal {
        // Additive-percent shortcut: `a + b%` and `a - b%` (only in ADDITIVE mode)
        if (percentMode == PercentMode.ADDITIVE && (expr.op == BinOp.ADD || expr.op == BinOp.SUB) && expr.right is PercentExpr) {
            val base = eval(expr.left)
            val pct = eval((expr.right as PercentExpr).operand)
            val delta = base.bdMultiply(pct, MC).bdDivide(HUNDRED, MC)
            return if (expr.op == BinOp.ADD) base.bdAdd(delta, MC) else base.bdSubtract(delta, MC)
        }

        val l = eval(expr.left)
        val r = eval(expr.right)

        val result = when (expr.op) {
            BinOp.ADD -> l.bdAdd(r, MC)
            BinOp.SUB -> l.bdSubtract(r, MC)
            BinOp.MUL -> l.bdMultiply(r, MC)
            BinOp.DIV -> {
                if (r.bdCompareTo(BigDecimalZero) == 0) throw EvalFail()
                l.bdDivide(r, MC)
            }
        }

        if (result.bdAbs().bdCompareTo(MAX_MAGNITUDE) > 0) throw EvalFail()
        return result
    }
}
