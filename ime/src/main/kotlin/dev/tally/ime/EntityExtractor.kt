package dev.tally.ime

/**
 * Identifies broad entity types from plain text using regular expressions.
 *
 * No networking, no ML, no third-party library. Patterns are intentionally conservative
 * (high precision, accepts some false negatives) to avoid mis-classifying ordinary text.
 *
 * Matching priority: URL > EMAIL > PHONE > ADDRESS > NONE.
 */
internal object EntityExtractor {

    // ── URL ──────────────────────────────────────────────────────────────────
    // Matches http(s):// or ftp:// URLs, plus bare www. prefixed domains.
    private val URL = Regex(
        """(?:https?://|ftp://|www\.)\S+""",
        RegexOption.IGNORE_CASE,
    )

    // ── EMAIL ─────────────────────────────────────────────────────────────────
    // RFC 5321 local-part is complex; this covers the practical 99% case.
    private val EMAIL = Regex(
        """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""",
    )

    // ── PHONE ─────────────────────────────────────────────────────────────────
    // Accepts international and North American formats.
    // Minimum 7 digits to avoid false positives on short numeric strings.
    private val PHONE = Regex(
        """(?:\+?\d[\d\s\-().]{6,}\d)""",
    )

    // ── ADDRESS ───────────────────────────────────────────────────────────────
    // Heuristic: starts with a street number followed by a word (street name).
    // This is a best-effort signal, not a geocoder.
    private val ADDRESS = Regex(
        """^\d{1,5}\s+[A-Za-z]""",
    )

    /**
     * Returns the most specific [EntityType] found in [text], or [EntityType.NONE].
     *
     * The check is applied to the trimmed input only; entity priority is URL > EMAIL > PHONE > ADDRESS.
     */
    fun classify(text: String): EntityType {
        val t = text.trim()
        return when {
            t.isEmpty() -> EntityType.NONE
            URL.containsMatchIn(t) -> EntityType.URL
            EMAIL.containsMatchIn(t) -> EntityType.EMAIL
            PHONE.containsMatchIn(t) && hasEnoughDigits(t) -> EntityType.PHONE
            ADDRESS.containsMatchIn(t) -> EntityType.ADDRESS
            else -> EntityType.NONE
        }
    }

    /** Require at least 7 digit characters to qualify as a phone number. */
    private fun hasEnoughDigits(text: String): Boolean =
        text.count { it.isDigit() } >= 7
}
