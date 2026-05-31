package dev.tally.math

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal class Formatter(private val locale: Locale) {
    companion object {
        private const val MAX_DISPLAY_SCALE = 6
        private const val MIN_DISPLAY_SCALE = 2
        private val SCIENTIFIC_HIGH = BigDecimal("1E15")
        private val SCIENTIFIC_LOW = BigDecimal("1E-6")
    }

    fun format(value: BigDecimal, maxInputScale: Int): String {
        val abs = value.abs()

        // Scientific notation for extreme magnitudes
        if (abs.compareTo(SCIENTIFIC_HIGH) >= 0 || (abs.compareTo(BigDecimal.ZERO) != 0 && abs.compareTo(SCIENTIFIC_LOW) < 0)) {
            return formatScientific(value)
        }

        // Display scale: honour input precision, with sensible floor and ceiling
        val displayScale = if (maxInputScale == 0) MAX_DISPLAY_SCALE
                           else maxInputScale.coerceIn(MIN_DISPLAY_SCALE, MAX_DISPLAY_SCALE)

        val rounded = value.setScale(displayScale, RoundingMode.HALF_EVEN).stripTrailingZeros()

        val nf = NumberFormat.getInstance(locale).apply {
            isGroupingUsed = true
        }

        return if (rounded.scale() <= 0) {
            // Integer result
            nf.apply { maximumFractionDigits = 0 }.format(rounded)
        } else {
            // Decimal result — strip trailing zeros already done by stripTrailingZeros()
            nf.apply {
                minimumFractionDigits = rounded.scale()
                maximumFractionDigits = rounded.scale()
            }.format(rounded)
        }
    }

    private fun formatScientific(value: BigDecimal): String {
        // Simple E-notation; locale decimal separator applied manually
        val s = value.toEngineeringString()
        val decimalSep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (decimalSep != '.') s.replace('.', decimalSep) else s
    }
}
