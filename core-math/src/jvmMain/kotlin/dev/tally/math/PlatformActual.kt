package dev.tally.math

// ---------------------------------------------------------------------------
// JVM actual declarations — typealias to java.math / java.text / java.util
//
// Typealias means JVM callers hold genuine java.* instances: no wrapping,
// no allocation overhead, no change to the compiled API surface.
//
// All expect extension functions use the "bd" prefix to avoid name collisions
// with the underlying java.math.BigDecimal methods, so actual bodies can
// call self.method() without recursion.
// ---------------------------------------------------------------------------

actual typealias RoundingMode = java.math.RoundingMode

actual typealias MathContext = java.math.MathContext

actual fun mathContextOf(precision: Int, roundingMode: RoundingMode): MathContext =
    java.math.MathContext(precision, roundingMode)

actual typealias BigDecimal = java.math.BigDecimal

actual fun bigDecimalOf(value: String): BigDecimal = java.math.BigDecimal(value)

actual fun bigDecimalOf(value: Int): BigDecimal = java.math.BigDecimal(value)

actual val BigDecimalZero: BigDecimal get() = java.math.BigDecimal.ZERO

actual val BigDecimalHundred: BigDecimal get() = java.math.BigDecimal("100")

actual fun BigDecimal.bdNegate(): BigDecimal = negate()

actual fun BigDecimal.bdAdd(augend: BigDecimal, mc: MathContext): BigDecimal = add(augend, mc)

actual fun BigDecimal.bdSubtract(subtrahend: BigDecimal, mc: MathContext): BigDecimal =
    subtract(subtrahend, mc)

actual fun BigDecimal.bdMultiply(multiplicand: BigDecimal, mc: MathContext): BigDecimal =
    multiply(multiplicand, mc)

actual fun BigDecimal.bdDivide(divisor: BigDecimal, mc: MathContext): BigDecimal =
    divide(divisor, mc)

actual fun BigDecimal.bdAbs(): BigDecimal = abs()

actual fun BigDecimal.bdSetScale(newScale: Int, roundingMode: RoundingMode): BigDecimal =
    setScale(newScale, roundingMode)

actual fun BigDecimal.bdStripTrailingZeros(): BigDecimal = stripTrailingZeros()

actual fun BigDecimal.bdScale(): Int = scale()

actual fun BigDecimal.bdCompareTo(other: BigDecimal): Int = compareTo(other)

actual fun BigDecimal.bdToEngineeringString(): String = toEngineeringString()

actual typealias Locale = java.util.Locale

actual fun defaultLocale(): Locale = java.util.Locale.getDefault()

actual fun decimalSeparatorFor(locale: Locale): Char =
    java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator

actual fun groupingSeparatorFor(locale: Locale): Char =
    java.text.DecimalFormatSymbols.getInstance(locale).groupingSeparator

actual fun formatDecimal(
    value: BigDecimal,
    locale: Locale,
    grouping: Boolean,
    minFractionDigits: Int,
    maxFractionDigits: Int,
): String {
    val nf = java.text.NumberFormat.getInstance(locale).apply {
        isGroupingUsed = grouping
        minimumFractionDigits = minFractionDigits
        maximumFractionDigits = maxFractionDigits
    }
    return nf.format(value)
}
