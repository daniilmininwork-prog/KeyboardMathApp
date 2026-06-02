package dev.tally.overlay

/**
 * Dormancy check for the overlay (TO.5, `02 §6`, AC-6).
 *
 * When Tally's own IME is the active keyboard the overlay is redundant — the `InputConnection`
 * path is strictly better — and it MUST produce zero chips. The active keyboard is read from
 * `Settings.Secure.DEFAULT_INPUT_METHOD`, whose value is a flattened `ComponentName` such as
 * `dev.tally/dev.tally.ime.TallyInputMethodService`. Matching on the service class name keeps the
 * check correct regardless of the host application id / build flavour.
 */
internal object OverlayDormancy {

    private const val TALLY_IME_SERVICE = "dev.tally.ime.TallyInputMethodService"

    fun isTallyImeActive(defaultInputMethod: String?): Boolean =
        defaultInputMethod?.contains(TALLY_IME_SERVICE) == true
}
