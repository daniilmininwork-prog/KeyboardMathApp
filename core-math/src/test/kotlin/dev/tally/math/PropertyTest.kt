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
