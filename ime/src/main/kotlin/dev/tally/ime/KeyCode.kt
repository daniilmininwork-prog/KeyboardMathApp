package dev.tally.ime

internal sealed class KeyCode {
    data class Char(val value: kotlin.Char) : KeyCode()
    object Backspace : KeyCode()
    object Enter : KeyCode()
    object Space : KeyCode()
    object Shift : KeyCode()
    object SwitchToNumeric : KeyCode()
    object SwitchToAlpha : KeyCode()
    object SwitchToSymbols : KeyCode()
    /** Toggle the optional number row at the top of the alphabetic layer. */
    object ToggleNumberRow : KeyCode()
}
