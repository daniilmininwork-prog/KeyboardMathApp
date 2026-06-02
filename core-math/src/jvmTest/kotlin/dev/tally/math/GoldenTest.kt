package dev.tally.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

class GoldenTest {

    companion object {
        private val EN_US = Locale.US

        @JvmStatic
        fun goldenCases(): Stream<Arguments> = Stream.of(
            // Canonical cases from the reference table
            Arguments.of("2+2=", "4", EN_US),
            Arguments.of("12 × 9=", "108", EN_US),
            Arguments.of("12 x 9=", "108", EN_US),
            Arguments.of("47.50/3=", "15.83", EN_US),
            Arguments.of("89*0.7=", "62.3", EN_US),
            Arguments.of("(2+3)*4=", "20", EN_US),
            Arguments.of("200+10%=", "220", EN_US),
            Arguments.of("1000000/8=", "125,000", EN_US),
            Arguments.of("0.1+0.2=", "0.3", EN_US),
            Arguments.of("the answer is 6*7=", "42", EN_US),

            // Operator precedence
            Arguments.of("2+3*4=", "14", EN_US),
            Arguments.of("10-2*3=", "4", EN_US),
            Arguments.of("12/4+1=", "4", EN_US),

            // Negatives
            Arguments.of("-5+3=", "-2", EN_US),
            Arguments.of("10+-3=", "7", EN_US),

            // Percent standalone and compound
            Arguments.of("200*15%=", "30", EN_US),
            Arguments.of("80-25%=", "60", EN_US),

            // Grouping separator on input
            Arguments.of("1,000+5=", "1,005", EN_US),
            Arguments.of("2,500*4=", "10,000", EN_US),

            // Division rounding (3-digit numerator avoids date-pattern veto)
            Arguments.of("100/3=", "33.333333", EN_US),
            Arguments.of("100/7=", "14.285714", EN_US),

            // Spacing variants
            Arguments.of("2 + 2=", "4", EN_US),
            Arguments.of("2 + 2 =", "4", EN_US),

            // Percent boundary cases
            Arguments.of("100+20%=", "120", EN_US),     // additive: 100 + (100×0.20) = 120
            Arguments.of("500-10%=", "450", EN_US),     // additive: 500 − (500×0.10) = 450
            Arguments.of("200*5%=", "10", EN_US),       // standalone: 200 × (5/100) = 10
            Arguments.of("400/25%=", "1,600", EN_US),   // standalone: 400 / (25/100) = 1600

            // Leading-prose golden case (the fragment is right-anchored)
            Arguments.of("price is 100+50=", "150", EN_US),

            // SCORE_RANGE veto boundary: single-digit A-B is vetoed (e.g. "1-0="), but
            // two-digit-or-more operands are valid arithmetic and must NOT be vetoed.
            // These cases confirm the veto remains narrow and does not suppress e.g. "10-7=".
            Arguments.of("10-7=", "3", EN_US),
            Arguments.of("99-1=", "98", EN_US),
        )

        @JvmStatic
        fun directPercentCases(): Stream<Arguments> = Stream.of(
            // DIRECT mode: percent always means ÷100, regardless of operator context.
            // `100 + 20%` = 100 + 0.20 = 100.2  (not 120 as in ADDITIVE mode)
            Arguments.of("100+20%=", "100.2", EN_US),
            // `500 - 10%` = 500 - 0.10 = 499.9
            Arguments.of("500-10%=", "499.9", EN_US),
            // `200 * 15%` = 200 * 0.15 = 30 (same as ADDITIVE for multiplication)
            Arguments.of("200*15%=", "30", EN_US),
            // `100 / 25%` = 100 / 0.25 = 400
            Arguments.of("100/25%=", "400", EN_US),
        )

        @JvmStatic
        fun noSuggestionCases(): Stream<Arguments> = Stream.of(
            // Division by zero
            Arguments.of("5/0=", EN_US),
            Arguments.of("0/0=", EN_US),

            // Version strings
            Arguments.of("iOS 17.4.1=", EN_US),
            Arguments.of("1.2.3=", EN_US),

            // Dates
            Arguments.of("2024-01-02=", EN_US),
            Arguments.of("9/11=", EN_US),
            Arguments.of("01/02/2024=", EN_US),

            // Phone-like
            Arguments.of("555-1234=", EN_US),
            Arguments.of("555-123-4567=", EN_US),

            // Lone number or incomplete expression
            Arguments.of("42=", EN_US),
            Arguments.of("5+=", EN_US),

            // No trailing equals
            Arguments.of("2+2", EN_US),
        )

        /**
         * Precision override test cases covering the [MathEngine.evaluate] `precision` parameter
         * and the [Formatter] `overridePrecision` path. These were previously uncovered in every
         * test file. The user-facing precision preference flows through
         * TallyInputMethodService.requestEvaluate → evaluator.onTextChanged(precision=prefs.precision).
         *
         * Note: single-digit divisors like "1/3=" are vetoed by the DATE_SHORT_SLASH pattern
         * (M/D). Use 3-digit dividends to avoid the veto.
         */
        private val DE_DE = Locale.GERMANY

        /**
         * Scientific-notation path (Formatter.formatScientific) golden cases.
         *
         * These are CRITICAL: formatScientific is called for values >= 1E15 or <= 1E-6 but no
         * golden test exercised that branch before (issue #20). A regression in
         * bdToEngineeringString() or the locale decimal-separator substitution inside
         * formatScientific would silently produce garbled display strings.
         */
        @JvmStatic
        fun scientificCases(): Stream<Arguments> = Stream.of(
            // High threshold (>= 1E15): 10_000_000_000_000_000 * 2 = 2E16 in en-US
            Arguments.of("10000000000000000*2=", EN_US),
            // Just above threshold: 1000000000000000+1= should also trigger scientific
            Arguments.of("1000000000000000+1000000000000000=", EN_US),
            // Low threshold (< 1E-6): 0.000001/2 = 5E-7 in en-US
            Arguments.of("0.000001/2=", EN_US),
            // de-DE high path: locale decimal separator must be comma in scientific notation
            Arguments.of("10000000000000000*2=", DE_DE),
            // de-DE low path: 0,000001/2 (comma-decimal input)
            Arguments.of("0,000001/2=", DE_DE),
        )

        @JvmStatic
        fun precisionCases(): Stream<Arguments> = Stream.of(
            // precision=2: 100/3 = 33.333... → "33.33"
            Arguments.of("100/3=", 2, "33.33"),
            // precision=0: 100/3 = 33.333... → "33" (rounds to integer)
            Arguments.of("100/3=", 0, "33"),
            // precision=4: 100/7 = 14.2857... → "14.2857"
            Arguments.of("100/7=", 4, "14.2857"),
            // precision=1: 47.50/3 = 15.833... → "15.8"
            Arguments.of("47.50/3=", 1, "15.8"),
        )

        /**
         * Display-scale boundary cases for the [Formatter] auto-scale path (issue #24).
         *
         * The displayScale coercion has three branches:
         *   - overridePrecision >= 0: user-pinned (covered by precisionCases above)
         *   - maxInputScale == 0: integer input → MAX_DISPLAY_SCALE=6
         *   - else: coerceIn(MIN_DISPLAY_SCALE=2, MAX_DISPLAY_SCALE=6)
         *
         * The MIN boundary (input scale 1 → output scale 2), exact-2 case, and MAX-clamp
         * (input scale 7 → output scale 6) are not covered by any existing golden case.
         * A change to MIN_DISPLAY_SCALE or MAX_DISPLAY_SCALE would not break any prior test.
         *
         * Format: (input, expectedDecimalCount)
         */
        @JvmStatic
        fun displayScaleBoundaryCases(): Stream<Arguments> = Stream.of(
            // input scale=1 → MIN_DISPLAY_SCALE=2: 100/3.0= has scale 1; result must show >=2 decimals
            // 100/3.0 = 33.33... → "33.33" (2 decimal places from MIN_DISPLAY_SCALE)
            Arguments.of("100/3.0=", "33.33"),
            // input scale=2 → exactly MIN: 100/3.00= → "33.33"
            Arguments.of("100/3.00=", "33.33"),
            // input scale=7 → clamped to MAX_DISPLAY_SCALE=6: 100/3.0000000= → "33.333333" (6)
            Arguments.of("100/3.0000000=", "33.333333"),
        )
    }

    @ParameterizedTest(name = "{0} → \"{1}\"")
    @MethodSource("goldenCases")
    fun `suggests correct result`(input: String, expected: String, locale: Locale) {
        val result = MathEngine.evaluate(input, locale)
        assertEquals(expected, result?.display, "for input: $input")
    }

    @ParameterizedTest(name = "{0} → no suggestion")
    @MethodSource("noSuggestionCases")
    fun `produces no suggestion`(input: String, locale: Locale) {
        assertNull(MathEngine.evaluate(input, locale), "expected null for: $input")
    }

    @ParameterizedTest(name = "DIRECT: {0} → \"{1}\"")
    @MethodSource("directPercentCases")
    fun `direct percent mode produces correct result`(input: String, expected: String, locale: Locale) {
        val result = MathEngine.evaluate(input, locale, percentMode = PercentMode.DIRECT)
        assertEquals(expected, result?.display, "for input: $input in DIRECT mode")
    }

    /**
     * Verifies that [Formatter.formatScientific] produces a non-null, non-blank E-notation
     * result for values >= 1E15 or <= 1E-6, and that the locale decimal separator is applied.
     *
     * These cases were completely untested before (issue #20). A regression in
     * bdToEngineeringString() or the locale decimal-separator substitution would silently
     * produce garbled display strings (e.g. '1.5E+15' rendered as '1,5E+15' in de-DE).
     */
    @ParameterizedTest(name = "scientific: {0} locale={1}")
    @MethodSource("scientificCases")
    fun `scientific notation path produces non-blank E-notation result`(
        input: String,
        locale: Locale,
    ) {
        val result = MathEngine.evaluate(input, locale)
        assertNotNull(result, "Expected a suggestion for scientific-notation input '$input' locale $locale")
        // The result must contain 'E' or 'e' (E-notation) — confirms formatScientific was called.
        val display = result!!.display
        assertTrue(
            display.contains('E', ignoreCase = true),
            "Expected E-notation in '$display' for input '$input' (locale $locale)"
        )
        // In de-DE the decimal separator must be ',' not '.'.
        if (locale == DE_DE) {
            assertFalse(
                display.contains('.') && display.indexOf('.') < display.indexOf('E', ignoreCase = true),
                "de-DE scientific result '$display' must not contain '.' as decimal separator"
            )
        }
    }

    /**
     * Covers the [MathEngine.evaluate] `precision` parameter and the [Formatter] `overridePrecision`
     * path. These are user-facing (TallyInputMethodService.requestEvaluate passes prefs.precision)
     * and were previously uncovered in every test file.
     */
    @ParameterizedTest(name = "precision={1}: {0} → \"{2}\"")
    @MethodSource("precisionCases")
    fun `precision override produces correctly rounded result`(
        input: String,
        precision: Int,
        expected: String,
    ) {
        val result = MathEngine.evaluate(input, EN_US, precision = precision)
        assertNotNull(result, "Expected a suggestion for '$input' with precision=$precision")
        assertEquals(expected, result!!.display, "for input: $input with precision=$precision")
    }

    /**
     * Verifies the [Formatter] display-scale coercion boundaries (issue #24).
     *
     * The MIN_DISPLAY_SCALE=2 and MAX_DISPLAY_SCALE=6 constants are formatting contracts.
     * A change to either constant would not break any previously-existing golden test.
     */
    @ParameterizedTest(name = "displayScale: {0} → \"{1}\"")
    @MethodSource("displayScaleBoundaryCases")
    fun `display scale is coerced to MIN-MAX range`(input: String, expected: String) {
        val result = MathEngine.evaluate(input, EN_US)
        assertNotNull(result, "Expected a suggestion for scale-boundary input '$input'")
        assertEquals(expected, result!!.display, "for scale-boundary input: $input")
    }
}
