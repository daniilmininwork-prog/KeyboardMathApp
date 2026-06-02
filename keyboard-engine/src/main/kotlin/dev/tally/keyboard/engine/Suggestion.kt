package dev.tally.keyboard.engine

/**
 * Discriminates the origin of a [Suggestion] so the strip can render and
 * prioritise candidates correctly.
 *
 * Ordinal ordering has no semantic meaning; the strip ranks by
 * [Suggestion.score] within the same kind, and by kind priority separately.
 */
enum class SuggestionKind {
    MATH,
    AUTOCORRECT,
    PREDICTION,
    NEXT_WORD,
    EMOJI,
    CLIPBOARD,
    INLINE_AUTOFILL,
}

/**
 * A single candidate surfaced by a [SuggestionSource] for display in the strip.
 *
 * All fields are pure JVM types — no Android APIs. The commit callback is absent
 * from this model; the strip layer resolves the commit action from the [kind] and
 * [text] at dispatch time.
 *
 * @param kind   Source category, used for visual styling and slot allocation.
 * @param text   The word or expression the user would commit by tapping this
 *               candidate. Shown as-is in the strip.
 * @param score  Relative quality estimate (higher = better). Used for intra-kind
 *               ranking and for tie-breaking across kinds that share a slot.
 *               Range is unspecified; only relative ordering matters.
 */
data class Suggestion(
    val kind: SuggestionKind,
    val text: String,
    val score: Float,
)
