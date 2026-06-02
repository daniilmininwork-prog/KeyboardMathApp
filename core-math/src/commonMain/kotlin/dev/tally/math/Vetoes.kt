package dev.tally.math

internal object Vetoes {
    // YYYY-MM-DD, YYYY/MM/DD, YYYY-MM
    private val DATE_ISO = Regex("""^\d{4}[-/]\d{1,2}([-/]\d{1,2})?$""")

    // M/D, M/D/YYYY, MM/DD/YYYY
    private val DATE_SHORT_SLASH = Regex("""^\d{1,2}/\d{1,2}(/\d{2,4})?$""")

    // DD-MM-YYYY or MM-DD-YYYY (dash-separated date with 4-digit year)
    private val DATE_DASH_FULL = Regex("""^\d{1,2}[-]\d{1,2}[-]\d{4}$""")

    // N.N.N or longer (version strings)
    private val VERSION = Regex("""^\d+(\.\d+){2,}$""")

    // NNN-NNNN or NNN-NNN-NNNN (local or full US phone)
    private val PHONE_US = Regex("""^\d{3}[-\s.]\d{3,4}$|^\d{3}[-\s.]\d{3}[-\s.]\d{4}$""")

    // International phone: optional +country, then 3-4 segments of digits separated by dashes/spaces.
    // Catches: +1-800-555-0100, 44-20-7946-0958, 1-800-555-0100.
    private val PHONE_INTL = Regex("""^\+?\d{1,3}[-\s]\d{1,3}[-\s]\d{3,5}[-\s]\d{4}$""")

    // ISBN-13 with dashes: 3-N-NN-NNNNNN-N (5 segments, starts with 978/979)
    private val ISBN = Regex("""^97[89][-]\d{1,5}[-]\d{1,7}[-]\d{1,7}[-]\d{1}$""")

    // Score / range: N-M where both sides are a single digit (0-9). Catches the canonical
    // sports-score pattern (1-0, 2-1, 0-0) noted in the spec. Kept narrow: two-digit
    // numbers on either side are allowed through as arithmetic since "10-7" is rarely a
    // score notation and more likely intended as subtraction.
    private val SCORE_RANGE = Regex("""^\d-\d$""")

    // Cross-locale grouping ambiguity: in a dot-decimal locale (en-US), a number like
    // 1.000 evaluates to 1.0 — but users from comma-decimal locales (de-DE, fr-FR) may
    // type 1.000 meaning 1000. Showing "6" for "1.000+5=" would be wrong for them, so
    // we conservatively suppress any expression containing N.DDD (positive integer part
    // followed by exactly 3 decimal digits) in locales where '.' is the decimal separator.
    private val GROUPING_AMBIGUOUS_DOT = Regex("""(?<![.,\d])[1-9]\d*\.\d{3}(?![.,\d])""")

    fun isVetoed(expressionText: String, locale: Locale = defaultLocale()): Boolean {
        val t = expressionText.trim()
        val decSep = decimalSeparatorFor(locale)
        return DATE_ISO.matches(t)
            || DATE_SHORT_SLASH.matches(t)
            || DATE_DASH_FULL.matches(t)
            || VERSION.matches(t)
            || PHONE_US.matches(t)
            || PHONE_INTL.matches(t)
            || ISBN.matches(t)
            || SCORE_RANGE.matches(t)
            || (decSep == '.' && GROUPING_AMBIGUOUS_DOT.containsMatchIn(t))
    }

    /** True if the character immediately before [spanStart] or at [spanEnd] is a letter. */
    fun isIdentifierAdjacent(original: String, spanStart: Int, spanEnd: Int): Boolean {
        val before = if (spanStart > 0) original[spanStart - 1] else null
        val after = if (spanEnd < original.length) original[spanEnd] else null
        return (before?.isLetter() == true) || (after?.isLetter() == true)
    }
}
