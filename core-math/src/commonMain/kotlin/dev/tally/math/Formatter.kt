package dev.tally.math

internal class Formatter(private val locale: Locale) {
    companion object {
        private const val MAX_DISPLAY_SCALE = 6
        private const val MIN_DISPLAY_SCALE = 2
        private val SCIENTIFIC_HIGH = bigDecimalOf("1E15")
        private val SCIENTIFIC_LOW = bigDecimalOf("1E-6")
    }

    // overridePrecision >= 0 pins the decimal places shown; -1 = auto (default)
    fun format(value: BigDecimal, maxInputScale: Int, overridePrecision: Int = -1): String {
        val abs = value.bdAbs()

        // Scientific notation for extreme magnitudes
        if (abs.bdCompareTo(SCIENTIFIC_HIGH) >= 0 || (abs.bdCompareTo(BigDecimalZero) != 0 && abs.bdCompareTo(SCIENTIFIC_LOW) < 0)) {
            return formatScientific(value)
        }

        // Display scale: user override wins; otherwise honour input precision
        val displayScale = when {
            overridePrecision >= 0 -> overridePrecision
            maxInputScale == 0    -> MAX_DISPLAY_SCALE
            else                  -> maxInputScale.coerceIn(MIN_DISPLAY_SCALE, MAX_DISPLAY_SCALE)
        }

        val rounded = value.bdSetScale(displayScale, RoundingMode.HALF_EVEN).bdStripTrailingZeros()
        val scale = rounded.bdScale()

        return if (scale <= 0) {
            // Integer result
            formatDecimal(rounded, locale, grouping = true, minFractionDigits = 0, maxFractionDigits = 0)
        } else {
            // Decimal result — strip trailing zeros already done by bdStripTrailingZeros()
            formatDecimal(rounded, locale, grouping = true, minFractionDigits = scale, maxFractionDigits = scale)
        }
    }

    private fun formatScientific(value: BigDecimal): String {
        // Simple E-notation; locale decimal separator applied manually
        val s = value.bdToEngineeringString()
        val decimalSep = decimalSeparatorFor(locale)
        return if (decimalSep != '.') s.replace('.', decimalSep) else s
    }
}
