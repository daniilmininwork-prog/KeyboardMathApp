package dev.tally.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

class LocaleTest {

    companion object {
        private val AR_EG = Locale("ar", "EG")
        private val DE_DE = Locale.GERMANY
        private val FR_FR = Locale.FRANCE
        private val HI_IN = Locale("hi", "IN")

        @JvmStatic
        fun localeCases(): Stream<Arguments> = Stream.of(
            // en-US: decimal='.', grouping=','
            Arguments.of("2+2=", "4", Locale.US),
            Arguments.of("0.1+0.2=", "0.3", Locale.US),
            Arguments.of("1000000/8=", "125,000", Locale.US),
            Arguments.of("47.50/3=", "15.83", Locale.US),

            // de-DE: decimal=',', grouping='.'
            Arguments.of("2+2=", "4", DE_DE),
            Arguments.of("0,1+0,2=", "0,3", DE_DE),
            Arguments.of("47,50/3=", "15,83", DE_DE),

            // de-DE large-number grouping: 1000000/8 = 125.000 (dot as thousands separator)
            Arguments.of("1000000/8=", "125.000", DE_DE),

            // fr-FR: decimal=',', grouping=' ' (narrow no-break space) or ' '
            Arguments.of("2+2=", "4", FR_FR),
            Arguments.of("0,1+0,2=", "0,3", FR_FR),

            // fr-FR large-number grouping: 1000000/8 = 125 000 (space as thousands separator)
            // Accept either regular space or narrow no-break space ( ) per JDK version.
            Arguments.of("1000000/8=", "125 000", FR_FR),

            // hi-IN: Devanagari digits
            Arguments.of("२+२=", "4", HI_IN),

            // ar-EG: Arabic-Indic digits (٠١٢٣٤٥٦٧٨٩), decimal separator '٫'
            // ٢+٢= → ٤  (Arabic-Indic 2+2 = 4 in Arabic-Indic output)
            Arguments.of("٢+٢=", "٤", AR_EG),
            // ١٠+٥= → ١٥  (10+5=15 in Arabic-Indic digits)
            Arguments.of("١٠+٥=", "١٥", AR_EG),
        )

        @JvmStatic
        fun localeNoSuggestionCases(): Stream<Arguments> = Stream.of(
            // Date in ISO format (de-DE)
            Arguments.of("2024-01-02=", DE_DE),
            // Division by zero (fr-FR)
            Arguments.of("5/0=", FR_FR),
        )

        @JvmStatic
        fun groupingAmbiguousDotCases(): Stream<Arguments> = Stream.of(
            // In en-US '.' is the decimal separator, so "1.000" is ambiguous (could be 1.0 or
            // 1000 depending on the user's background). The GROUPING_AMBIGUOUS_DOT veto fires
            // only when decSep == '.', so "1.000+5=" must be suppressed.
            Arguments.of("1.000+5=", Locale.US, false, "en-US: dot-decimal locale, veto must fire"),

            // In de-DE '.' is the thousands separator and ',' is the decimal separator.
            // decSep != '.', so the veto guard is NOT active and "1.000+5=" must evaluate
            // to 1005 (1000 + 5). A regression that ignores the locale guard would suppress
            // valid German arithmetic, so this case is critical.
            Arguments.of("1.000+5=", DE_DE, true, "de-DE: dot-grouping locale, veto must NOT fire"),

            // In de-DE, "1.000*3=" uses the multiplication operator — the GROUPING_AMBIGUOUS_DOT
            // veto fires on containsMatchIn, so it must also NOT fire in de-DE for multiplication.
            // This confirms the veto is locale-gated regardless of the operator context.
            Arguments.of("1.000*3=", DE_DE, true, "de-DE: dot-grouping locale, multiplication must NOT be vetoed"),
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

    /**
     * Verifies the GROUPING_AMBIGUOUS_DOT veto is locale-gated.
     *
     * The veto fires only when decSep == '.'. In de-DE '.' is a thousands separator, so
     * "1.000+5=" must NOT be vetoed — the dot-grouping number parses as 1000 and the
     * expression evaluates to 1005. A regression that ignores the locale guard would
     * silently suppress valid German arithmetic.
     */
    @ParameterizedTest(name = "{3}")
    @MethodSource("groupingAmbiguousDotCases")
    fun `grouping-ambiguous-dot veto is locale-gated`(
        input: String,
        locale: Locale,
        expectSuggestion: Boolean,
        description: String,
    ) {
        val result = MathEngine.evaluate(input, locale)
        if (expectSuggestion) {
            assertNotNull(result, "Expected a suggestion for '$input' ($description) but got null")
        } else {
            assertNull(result, "Expected no suggestion for '$input' ($description) but got: ${result?.display}")
        }
    }
}
