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
         *
         * Note on Java's BigDecimal.toEngineeringString(): for exact large integers (e.g. 2E16)
         * it returns the plain decimal representation without 'E' (e.g. "20000000000000000").
         * Only numbers stored with an explicit exponent or fractional part produce 'E' notation.
         * Therefore we use:
         *   - Small-number path (< 1E-6): 0.000001/2 → 5E-7 → "500E-9" (contains 'E') ✓
         *   - Small-number de-DE path: 0,000001/2 → 5E-7 → "500E-9" ✓
         * The large-integer path (>= 1E15) is verified separately (scientificLargeIntegerCases).
         */
        @JvmStatic
        fun scientificCases(): Stream<Arguments> = Stream.of(
            // Low threshold (< 1E-6): 0.000001/2 = 5E-7 → "500E-9" in en-US (contains 'E')
            Arguments.of("0.000001/2=", EN_US),
            // de-DE low path: comma-decimal input, result must use comma separator for de-DE
            Arguments.of("0,000001/2=", DE_DE),
        )

        /**
         * Verifies that the scientific-notation threshold fires for large integers (>= 1E15).
         *
         * Java's BigDecimal.toEngineeringString() returns a plain decimal for exact large
         * integers (e.g. "20000000000000000" instead of "20E+15"). The Formatter still routes
         * through formatScientific for these values (the threshold check is correct), but the
         * returned string is the plain decimal representation without an 'E'. The important
         * assertion here is:
         *   - The result is NOT formatted with the locale grouping separator (no commas for
         *     en-US), proving formatDecimal was NOT called with grouping=true.
         *   - The result IS returned (not null).
         * This catches a regression where the scientific check was disabled/removed.
         */
        @JvmStatic
        fun scientificLargeIntegerCases(): Stream<Arguments> = Stream.of(
            // 10_000_000_000_000_000 * 2 = 20000000000000000 (exact integer > 1E15)
            // toEngineeringString() returns "20000000000000000" (no 'E'); assert non-null and
            // that the result does NOT have en-US comma grouping ("20,000,000,000,000,000").
            Arguments.of("10000000000000000*2=", EN_US, "20000000000000000"),
            Arguments.of("1000000000000000+1000000000000000=", EN_US, "2000000000000000"),
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
     * Verifies that [Formatter.formatScientific] produces E-notation for values <= 1E-6.
     *
     * These cases were completely untested before (issue #20). A regression in
     * bdToEngineeringString() or the locale decimal-separator substitution inside
     * formatScientific would silently produce garbled display strings.
     *
     * Note: Java's BigDecimal.toEngineeringString() returns a plain decimal for exact large
     * integers (e.g. "20000000000000000" not "20E+15"). Large-integer cases are covered by
     * [scientific notation fires for large integers — result is non-null without comma grouping].
     */
    @ParameterizedTest(name = "scientific small: {0} locale={1}")
    @MethodSource("scientificCases")
    fun `scientific notation path produces E-notation for sub-1E-6 results`(
        input: String,
        locale: Locale,
    ) {
        val result = MathEngine.evaluate(input, locale)
        assertNotNull(result, "Expected a suggestion for scientific input '$input' locale $locale")
        val display = result!!.display
        // Small-number results from toEngineeringString() always contain 'E' (e.g. "500E-9").
        assertTrue(
            display.contains('E', ignoreCase = true),
            "Expected E-notation in '$display' for sub-1E-6 input '$input' (locale $locale)"
        )
    }

    /**
     * Verifies that the scientific threshold fires for large integers (>= 1E15).
     *
     * Java's toEngineeringString() for exact large integers returns a plain decimal
     * (e.g. "20000000000000000" not "20E+15"). The key regression signal is that the
     * result does NOT have the locale grouping separator — formatScientific is called
     * (not formatDecimal with grouping=true), so there are no commas in en-US output.
     */
    @ParameterizedTest(name = "scientific large int: {0} → {2}")
    @MethodSource("scientificLargeIntegerCases")
    fun `scientific notation fires for large integers — result is non-null without comma grouping`(
        input: String,
        locale: Locale,
        expected: String,
    ) {
        val result = MathEngine.evaluate(input, locale)
        assertNotNull(result, "Expected a suggestion for large-integer input '$input'")
        assertEquals(expected, result!!.display,
            "Large-integer result must not have grouping separators (formatScientific does not add commas)")
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
