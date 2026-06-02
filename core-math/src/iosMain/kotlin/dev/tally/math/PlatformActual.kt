package dev.tally.math

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// ---------------------------------------------------------------------------
// iOS / Kotlin-Native actual declarations
//
// RoundingMode and MathContext are pure Kotlin types (no ObjC equivalent).
//
// BigDecimal is backed by NSDecimalNumber (128-bit BCD, 38 significant digits),
// which is more than sufficient for keyboard-chip arithmetic. Division by zero
// produces NSDecimalNumber.notANumber and is caught by the runCatching() guards
// in Evaluator.
//
// Locale and formatting are backed by NSLocale / NSNumberFormatter.
// ---------------------------------------------------------------------------

// RoundingMode: pure Kotlin enum, no platform equivalent needed.
actual enum class RoundingMode {
    HALF_EVEN,
    FLOOR,
    CEILING,
    UP,
    DOWN,
    HALF_UP,
    HALF_DOWN,
    UNNECESSARY,
}

// MathContext: lightweight carrier for precision + rounding intent.
// NSDecimalNumber manages precision internally (38 sig digits), so this is
// only needed for API compatibility with commonMain.
actual class MathContext(val precision: Int, val roundingMode: RoundingMode)

actual fun mathContextOf(precision: Int, roundingMode: RoundingMode): MathContext =
    MathContext(precision, roundingMode)

// ---------------------------------------------------------------------------
// BigDecimal — NSDecimalNumber wrapper
// ---------------------------------------------------------------------------

actual class BigDecimal internal constructor(internal val nsValue: NSDecimalNumber) {
    override fun toString(): String = nsValue.stringValue

    override fun equals(other: Any?): Boolean =
        other is BigDecimal && bdCompareTo(other) == 0

    override fun hashCode(): Int = nsValue.stringValue.hashCode()
}

actual fun bigDecimalOf(value: String): BigDecimal =
    BigDecimal(NSDecimalNumber(string = value))

actual fun bigDecimalOf(value: Int): BigDecimal =
    BigDecimal(NSDecimalNumber(double = value.toDouble()))

actual val BigDecimalZero: BigDecimal get() = BigDecimal(NSDecimalNumber.zero)

actual val BigDecimalHundred: BigDecimal get() = BigDecimal(NSDecimalNumber(string = "100"))

actual fun BigDecimal.bdNegate(): BigDecimal =
    BigDecimal(nsValue.decimalNumberByMultiplyingBy(NSDecimalNumber(string = "-1")))

actual fun BigDecimal.bdAdd(augend: BigDecimal, mc: MathContext): BigDecimal =
    BigDecimal(nsValue.decimalNumberByAdding(augend.nsValue))

actual fun BigDecimal.bdSubtract(subtrahend: BigDecimal, mc: MathContext): BigDecimal =
    BigDecimal(nsValue.decimalNumberBySubtracting(subtrahend.nsValue))

actual fun BigDecimal.bdMultiply(multiplicand: BigDecimal, mc: MathContext): BigDecimal =
    BigDecimal(nsValue.decimalNumberByMultiplyingBy(multiplicand.nsValue))

actual fun BigDecimal.bdDivide(divisor: BigDecimal, mc: MathContext): BigDecimal =
    BigDecimal(nsValue.decimalNumberByDividingBy(divisor.nsValue))

actual fun BigDecimal.bdAbs(): BigDecimal {
    // NSDecimalNumber has no abs(); use negation for negative values.
    return if (nsValue.compare(NSDecimalNumber.zero) < 0) bdNegate() else BigDecimal(nsValue)
}

actual fun BigDecimal.bdSetScale(newScale: Int, roundingMode: RoundingMode): BigDecimal {
    // Round to the requested scale by formatting at that precision and re-parsing.
    val d = nsValue.doubleValue
    val factor = 10.0.pow(newScale.toDouble())
    val rounded = kotlin.math.round(d * factor) / factor
    val s = buildString {
        append(rounded.toLong())
        if (newScale > 0) {
            append('.')
            val frac = (abs(rounded) - abs(rounded.toLong().toDouble()))
            var fracStr = (frac * factor).toLong().toString().padStart(newScale, '0')
            if (fracStr.length > newScale) fracStr = fracStr.substring(0, newScale)
            append(fracStr)
        }
    }
    return BigDecimal(NSDecimalNumber(string = s))
}

actual fun BigDecimal.bdStripTrailingZeros(): BigDecimal {
    val s = nsValue.stringValue
    if (!s.contains('.')) return this
    val stripped = s.trimEnd('0').trimEnd('.')
    return BigDecimal(NSDecimalNumber(string = stripped.ifEmpty { "0" }))
}

actual fun BigDecimal.bdScale(): Int {
    val s = nsValue.stringValue
    val dot = s.indexOf('.')
    return if (dot < 0) 0 else s.length - dot - 1
}

actual fun BigDecimal.bdCompareTo(other: BigDecimal): Int =
    nsValue.compare(other.nsValue).toInt()

actual fun BigDecimal.bdToEngineeringString(): String {
    // Engineering notation (exponent is a multiple of 3).
    // Called only for extreme values (>= 1E15 or <= 1E-6) by Formatter.
    val d = nsValue.doubleValue
    if (d == 0.0) return "0"
    val absD = abs(d)
    val exp = floor(log10(absD)).toInt()
    val engExp = (exp / 3) * 3
    val mantissa = d / 10.0.pow(engExp.toDouble())
    val sign = if (engExp >= 0) "+" else ""
    return "${mantissa}E${sign}${engExp}"
}

// ---------------------------------------------------------------------------
// Locale
// ---------------------------------------------------------------------------

actual class Locale internal constructor(internal val nsLocale: NSLocale) {
    override fun toString(): String = nsLocale.description ?: "und"
}

actual fun defaultLocale(): Locale = Locale(NSLocale.currentLocale)

// ---------------------------------------------------------------------------
// Decimal formatting helpers
// ---------------------------------------------------------------------------

actual fun decimalSeparatorFor(locale: Locale): Char {
    val fmt = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        this.locale = locale.nsLocale
    }
    return fmt.decimalSeparator.firstOrNull() ?: '.'
}

actual fun groupingSeparatorFor(locale: Locale): Char {
    val fmt = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        this.locale = locale.nsLocale
    }
    return fmt.groupingSeparator.firstOrNull() ?: ','
}

actual fun formatDecimal(
    value: BigDecimal,
    locale: Locale,
    grouping: Boolean,
    minFractionDigits: Int,
    maxFractionDigits: Int,
): String {
    val fmt = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        this.locale = locale.nsLocale
        usesGroupingSeparator = grouping
        minimumFractionDigits = minFractionDigits.toULong()
        maximumFractionDigits = maxFractionDigits.toULong()
    }
    return fmt.stringFromNumber(value.nsValue) ?: value.nsValue.stringValue
}
