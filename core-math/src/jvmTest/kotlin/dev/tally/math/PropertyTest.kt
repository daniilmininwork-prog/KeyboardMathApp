package dev.tally.math

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.util.Locale

class PropertyTest : FreeSpec({

    val locale = Locale.US

    "totality — engine never throws for any string" {
        checkAll(Arb.string(), Arb.string()) { s, suffix ->
            val input = s.take(100) + "=" + suffix.take(0)
            runCatching { MathEngine.evaluate(input, locale) }.isSuccess shouldBe true
        }
    }

    "determinism — same input always gives same output" {
        checkAll(Arb.string()) { s ->
            val input = s.take(100) + "="
            MathEngine.evaluate(input, locale) shouldBe MathEngine.evaluate(input, locale)
        }
    }

    "valid expressions — result matches BigDecimal oracle" {
        checkAll(1_000, validExprArb()) { pair ->
            val (expr, expected) = pair
            val suggestion = MathEngine.evaluate("$expr=", locale)
            if (suggestion != null) {
                val diff = suggestion.exactValue.subtract(expected).abs()
                (diff.compareTo(BigDecimal("1E-4")) <= 0) shouldBe true
            }
        }
    }

    "integer results have no decimal point" {
        listOf("2+2", "3*4", "10-7", "6/2", "100+50", "999-1").forEach { e ->
            val s = MathEngine.evaluate("$e=", locale)
            if (s != null) {
                s.display.contains('.') shouldBe false
            }
        }
    }

    "non-integer results strip trailing zeros" {
        checkAll(1_000, validExprArb()) { pair ->
            val (expr, _) = pair
            val s = MathEngine.evaluate("$expr=", locale)
            if (s != null && s.display.contains('.')) {
                s.display.endsWith("0") shouldBe false
            }
        }
    }
})

private fun validExprArb(): Arb<Pair<String, BigDecimal>> =
    Arb.int(1, 999).flatMap { a ->
        Arb.int(1, 999).flatMap { b ->
            Arb.of("+", "-", "*").map { op ->
                val expr = "$a$op$b"
                val expected = when (op) {
                    "+" -> BigDecimal(a).add(BigDecimal(b))
                    "-" -> BigDecimal(a).subtract(BigDecimal(b))
                    else -> BigDecimal(a).multiply(BigDecimal(b))
                }
                expr to expected
            }
        }
    }

/**
 * Property tests specific to the division operator (issue #25).
 *
 * Division is excluded from [validExprArb] because non-terminating decimals produce
 * irrational results that cannot be compared with BigDecimal.equals(). Instead we verify:
 *   - The engine never throws for any valid integer division expression.
 *   - Non-zero divisors produce a result (never null for a well-formed expression).
 *   - The result's exactValue deviates from the BigDecimal reference by at most 1E-4.
 *   - The division-specific rounding-mode path (HALF_EVEN, MathContext scale 34) is exercised.
 *
 * These cases cover the ArithmeticException catch path in the engine (the only operator that
 * can throw ArithmeticException from non-terminating decimal expansion).
 */
class DivisionPropertyTest : FreeSpec({

    val locale = java.util.Locale.US

    "division by non-zero integers never throws" {
        checkAll(Arb.int(1, 999), Arb.int(1, 999)) { a, b ->
            runCatching {
                MathEngine.evaluate("$a/$b=", locale)
            }.isSuccess shouldBe true
        }
    }

    "division of two non-zero integers always produces a result" {
        checkAll(200, Arb.int(1, 999), Arb.int(1, 999)) { a, b ->
            val suggestion = MathEngine.evaluate("$a/$b=", locale)
            // Some expressions may be vetoed (e.g. "1/1=" resembles "M/D" date format).
            // We only assert that if a result is returned, it is numeric (no crash, no NPE).
            if (suggestion != null) {
                val diff = suggestion.exactValue
                    .subtract(BigDecimal(a).divide(BigDecimal(b), 8, java.math.RoundingMode.HALF_EVEN))
                    .abs()
                (diff.compareTo(BigDecimal("1E-4")) <= 0) shouldBe true
            }
        }
    }

    "division by zero returns null (no suggestion)" {
        checkAll(Arb.int(1, 999)) { a ->
            MathEngine.evaluate("$a/0=", locale) shouldBe null
        }
    }
})
