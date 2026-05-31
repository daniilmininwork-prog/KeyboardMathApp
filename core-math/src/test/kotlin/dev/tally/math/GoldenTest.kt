package dev.tally.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
