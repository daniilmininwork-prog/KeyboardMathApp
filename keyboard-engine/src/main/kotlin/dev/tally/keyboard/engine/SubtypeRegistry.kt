package dev.tally.keyboard.engine

/**
 * Hard-coded catalog of the subtypes that ship with this build.
 *
 * Each entry maps a stable [SubtypeModel.id] to a layout JSON asset path. New languages
 * must be added here AND backed by a corresponding data-ledger entry (T5.1 acceptance
 * criterion: first non-English language ships only with vetted permissive lexicon+LM, and
 * the ledger entry precedes code). Layouts that lack a vetted lexicon still appear here for
 * the globe switcher but are not wired to any decoder; the decoder falls back to English
 * candidates.
 *
 * This object has no Android imports. The IME layer references it to build the [SubtypeList]
 * and to resolve the layout asset path for [LayoutLoader].
 */
object SubtypeRegistry {

    /** All subtypes available in this build, in the order they cycle via the globe key. */
    val ALL: List<SubtypeModel> = listOf(
        SubtypeModel(
            id        = "en_US_QWERTY",
            locale    = "en-US",
            label     = "English (US)",
            direction = Direction.LTR,
        ),
        SubtypeModel(
            id        = "fr_FR_AZERTY",
            locale    = "fr-FR",
            label     = "Français (FR)",
            direction = Direction.LTR,
        ),
        SubtypeModel(
            id        = "de_DE_QWERTZ",
            locale    = "de-DE",
            label     = "Deutsch (DE)",
            direction = Direction.LTR,
        ),
    )

    /**
     * Returns the layout JSON asset path for [subtype] (relative to the assets root).
     *
     * The convention is `layouts/<id>.json`; this keeps the path derivable from the id
     * without a separate lookup table.
     */
    fun assetPath(subtype: SubtypeModel): String = "layouts/${subtype.id}.json"
}
