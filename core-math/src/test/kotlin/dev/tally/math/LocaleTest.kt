package dev.tally.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

class LocaleTest {

    companion object {
        @JvmStatic
        fun localeCases(): Stream<Arguments> = Stream.of(
            // en-US: decimal='.', grouping=','
            Arguments.of("2+2=", "4", Locale.US),
            Arguments.of("0.1+0.2=", "0.3", Locale.US),
            Arguments.of("1000000/8=", "125,000", Locale.US),
            Arguments.of("47.50/3=", "15.83", Locale.US),

            // de-DE: decimal=',', grouping='.'
            Arguments.of("2+2=", "4", Locale.GERMANY),
            Arguments.of("0,1+0,2=", "0,3", Locale.GERMANY),
            Arguments.of("47,50/3=", "15,83", Locale.GERMANY),

            // fr-FR: decimal=',', grouping=' '
            Arguments.of("2+2=", "4", Locale.FRANCE),
            Arguments.of("0,1+0,2=", "0,3", Locale.FRANCE),

            // hi-IN: Devanagari digits
            Arguments.of("२+२=", "4", Locale("hi", "IN")),
        )

        @JvmStatic
        fun localeNoSuggestionCases(): Stream<Arguments> = Stream.of(
            // Date in ISO format (de-DE)
            Arguments.of("2024-01-02=", Locale.GERMANY),
            // Division by zero (fr-FR)
            Arguments.of("5/0=", Locale.FRANCE),
        )
    }

    @ParameterizedTest(name = "{2}: \"{0}\" → \"{1}\"")
    @MethodSource("localeCases")
    fun `locale-aware result`(input: String, expected: String, locale: Locale) {
        val result = MathEngine.evaluate(input, locale)
        assertEquals(expected, result?.display, "for input '$input' locale $locale")
    }

    @ParameterizedTest(name = "{1}: \"{0}\" → no suggestion")
    @MethodSource("localeNoSuggestionCases")
    fun `locale-aware no suggestion`(input: String, locale: Locale) {
        assertNull(MathEngine.evaluate(input, locale), "expected null for '$input' locale $locale")
    }
}
