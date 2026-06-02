package dev.tally.math

// ---------------------------------------------------------------------------
// Platform-type contracts
//
// These expect declarations provide platform-specific types and operations
// needed by the core-math engine.
//
// Design rules:
//   • Types are declared as opaque expect classes with no members — this
//     allows JVM to use actual typealias (zero overhead, identical runtime
//     type) without hitting the "actualization to Java field" restriction.
//   • All operations are exposed as standalone expect functions with a "bd"
//     prefix (for BigDecimal ops) or plain names (for helpers). The prefix
//     avoids name collisions with the aliased Java class methods on JVM,
//     which would otherwise cause infinite recursion in actual bodies.
// ---------------------------------------------------------------------------

/** Rounding rule applied to BigDecimal operations. */
expect enum class RoundingMode {
    HALF_EVEN,
    FLOOR,
    CEILING,
    UP,
    DOWN,
    HALF_UP,
    HALF_DOWN,
    UNNECESSARY,
}

/**
 * Precision and rounding policy for BigDecimal arithmetic.
 *
 * Opaque: no declared members, accessed only via [mathContextOf].
 */
expect class MathContext

/** Constructs a [MathContext] with the given digit precision and rounding mode. */
expect fun mathContextOf(precision: Int, roundingMode: RoundingMode): MathContext

/**
 * Arbitrary-precision signed decimal number.
 *
 * Opaque: no declared members. All arithmetic is via the bd* extension functions
 * below. The "bd" prefix prevents name collisions with java.math.BigDecimal methods
 * when JVM uses actual typealias.
 */
expect class BigDecimal

/** Parses a plain-decimal or E-notation string (e.g. "3.14", "1E+6") into a [BigDecimal]. */
expect fun bigDecimalOf(value: String): BigDecimal

/** Converts an integer to a [BigDecimal]. */
expect fun bigDecimalOf(value: Int): BigDecimal

/** The BigDecimal value zero. */
expect val BigDecimalZero: BigDecimal

/** The BigDecimal value one hundred. */
expect val BigDecimalHundred: BigDecimal

/** Returns the negation of this value. */
expect fun BigDecimal.bdNegate(): BigDecimal

/** Returns this + [augend] under [mc] precision. */
expect fun BigDecimal.bdAdd(augend: BigDecimal, mc: MathContext): BigDecimal

/** Returns this − [subtrahend] under [mc] precision. */
expect fun BigDecimal.bdSubtract(subtrahend: BigDecimal, mc: MathContext): BigDecimal

/** Returns this × [multiplicand] under [mc] precision. */
expect fun BigDecimal.bdMultiply(multiplicand: BigDecimal, mc: MathContext): BigDecimal

/** Returns this ÷ [divisor] under [mc] precision. Throws if [divisor] is zero. */
expect fun BigDecimal.bdDivide(divisor: BigDecimal, mc: MathContext): BigDecimal

/** Returns the absolute value. */
expect fun BigDecimal.bdAbs(): BigDecimal

/** Rounds to [newScale] decimal places using [roundingMode]. */
expect fun BigDecimal.bdSetScale(newScale: Int, roundingMode: RoundingMode): BigDecimal

/** Removes trailing fractional zeros. */
expect fun BigDecimal.bdStripTrailingZeros(): BigDecimal

/** Number of digits to the right of the decimal point (may be negative for integers). */
expect fun BigDecimal.bdScale(): Int

/** Three-way comparison: negative / zero / positive. */
expect fun BigDecimal.bdCompareTo(other: BigDecimal): Int

/** Engineering-notation string (e.g. "1.23E+6"). */
expect fun BigDecimal.bdToEngineeringString(): String

// ---------------------------------------------------------------------------
// Locale
// ---------------------------------------------------------------------------

/**
 * Locale identifier. Opaque; passed to formatting helpers.
 *
 * On JVM this is a typealias to java.util.Locale — callers hold the real type.
 */
expect class Locale

/** Returns the device's default locale. */
expect fun defaultLocale(): Locale

// ---------------------------------------------------------------------------
// Decimal formatting helpers
// ---------------------------------------------------------------------------

/** Returns the decimal separator character for [locale] (e.g. '.' for en-US). */
expect fun decimalSeparatorFor(locale: Locale): Char

/** Returns the grouping separator character for [locale] (e.g. ',' for en-US). */
expect fun groupingSeparatorFor(locale: Locale): Char

/**
 * Formats [value] with locale-aware grouping and the specified fraction-digit range.
 *
 * @param grouping          whether to insert grouping separators
 * @param minFractionDigits minimum digits after the decimal point
 * @param maxFractionDigits maximum digits after the decimal point (≥ [minFractionDigits])
 */
expect fun formatDecimal(
    value: BigDecimal,
    locale: Locale,
    grouping: Boolean,
    minFractionDigits: Int,
    maxFractionDigits: Int,
): String
