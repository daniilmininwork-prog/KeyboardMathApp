package dev.tally.math

internal object Vetoes {
    // YYYY-MM-DD, YYYY/MM/DD, YYYY-MM
    private val DATE_ISO = Regex("""^\d{4}[-/]\d{1,2}([-/]\d{1,2})?$""")

    // M/D, M/D/YYYY, MM/DD/YYYY
    private val DATE_SHORT_SLASH = Regex("""^\d{1,2}/\d{1,2}(/\d{2,4})?$""")

    // N.N.N or longer (version strings)
    private val VERSION = Regex("""^\d+(\.\d+){2,}$""")

    // NNN-NNNN or NNN-NNN-NNNN (local or full phone)
    private val PHONE = Regex("""^\d{3}[-\s.]\d{3,4}$|^\d{3}[-\s.]\d{3}[-\s.]\d{4}$""")

    fun isVetoed(expressionText: String): Boolean {
        val t = expressionText.trim()
        return DATE_ISO.matches(t)
            || DATE_SHORT_SLASH.matches(t)
            || VERSION.matches(t)
            || PHONE.matches(t)
    }

    /** True if the character immediately before [spanStart] or at [spanEnd] is a letter. */
    fun isIdentifierAdjacent(original: String, spanStart: Int, spanEnd: Int): Boolean {
        val before = if (spanStart > 0) original[spanStart - 1] else null
        val after = if (spanEnd < original.length) original[spanEnd] else null
        return (before?.isLetter() == true) || (after?.isLetter() == true)
    }
}
