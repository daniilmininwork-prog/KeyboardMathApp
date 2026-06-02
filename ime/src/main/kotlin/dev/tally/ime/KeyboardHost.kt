package dev.tally.ime

/**
 * Thin abstraction over the keyboard host so feature-glue (Phase 3) is decoupled from the
 * concrete [TallyInputMethodService]. Swap the host by changing a single binding; the feature
 * code never needs to change.
 */
interface KeyboardHost {
    /** Returns up to [maxLength] characters immediately before the cursor, or null. */
    fun getTextBeforeCursor(maxLength: Int): CharSequence?

    /** Inserts [text] at the current cursor position, moving the cursor after it. */
    fun insertResult(text: String)

    /** Signals that the suggestion chip should be hidden (consumed or expression became invalid). */
    fun clearSuggestion()
}
