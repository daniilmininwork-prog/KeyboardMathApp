package dev.tally.keyboard.engine

/**
 * A single input method subtype — one language + layout pair.
 *
 * Immutable value type. No Android imports; the IME layer maps these to
 * [android.view.inputmethod.InputMethodSubtype] at the system boundary.
 *
 * @param id        Stable identifier matching the layout JSON filename stem (e.g. "en_US_QWERTY").
 * @param locale    BCP-47 locale tag (e.g. "en-US", "fr-FR").
 * @param label     Short human-readable name shown on the globe-key long-press menu.
 * @param direction Text direction for this layout.
 */
data class SubtypeModel(
    val id: String,
    val locale: String,
    val label: String,
    val direction: Direction,
)

/**
 * Ordered list of enabled subtypes with cycle-next semantics.
 *
 * The list is immutable after construction. Cycling always wraps around so there is no
 * terminal state — the globe key can be pressed indefinitely.
 *
 * @throws IllegalArgumentException if [subtypes] is empty.
 */
class SubtypeList(val subtypes: List<SubtypeModel>) {

    init {
        require(subtypes.isNotEmpty()) { "SubtypeList must contain at least one subtype" }
    }

    /**
     * Returns the subtype that follows [current] in the list, wrapping to the first entry
     * when [current] is the last one.
     *
     * If [current] is not found in the list the first subtype is returned — this handles
     * the case where a previously-selected subtype has been removed from the enabled set.
     */
    fun next(current: SubtypeModel): SubtypeModel {
        val idx = subtypes.indexOf(current)
        return if (idx < 0) subtypes[0] else subtypes[(idx + 1) % subtypes.size]
    }

    /**
     * Returns the subtype whose [SubtypeModel.id] matches [id], or null when no match exists.
     */
    fun findById(id: String): SubtypeModel? = subtypes.firstOrNull { it.id == id }
}
