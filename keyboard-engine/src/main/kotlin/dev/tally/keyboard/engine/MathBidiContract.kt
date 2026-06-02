package dev.tally.keyboard.engine

/**
 * Bidi-safety contract for math expression display inside RTL text fields.
 *
 * Arithmetic expressions and their results are intrinsically LTR: "12+3=15" must
 * never render reversed to "51=3+21" inside an Arabic or Hebrew field. The Unicode
 * Bidi Algorithm will typically handle numeric runs correctly, but the surrounding
 * RTL paragraph direction can cause edge cases when the expression appears at a
 * paragraph boundary or when the result string is used as a standalone chip label.
 *
 * Contract (05-device-framework-compatibility.md §5.1):
 *   - Any string produced by the math engine that will be displayed or inserted into
 *     an editor MUST be wrapped with [wrapLtr] before use.
 *   - This wrapping is a rendering hint, not a content change: it inserts Unicode
 *     directional isolate marks (LRI / PDI, U+2066 / U+2069) around the content.
 *   - Callers in the Android UI layer (suggestion strip, chip label, commitText) must
 *     call [wrapLtr] on the display string when the active field layout direction is RTL.
 *   - [wrapLtr] is idempotent: wrapping an already-wrapped string a second time is safe
 *     (the inner LRI-PDI pair is preserved without doubling).
 *
 * Why LRI/PDI rather than LRM?
 *   - U+200E (LEFT-TO-RIGHT MARK) is a character-level hint that can be stripped
 *     by editors on copy-paste, BiDi-unaware text processors, or R8 constant folding.
 *   - U+2066 (LEFT-TO-RIGHT ISOLATE) / U+2069 (POP DIRECTIONAL ISOLATE) form a
 *     directional isolate scope that is robust to paragraph direction changes and is
 *     recommended by Unicode Bidi Algorithm (UBA) §6.3 for embedding inline values
 *     inside an opposite-direction paragraph.
 *
 * This is a pure-JVM utility (no Android imports).
 */
object MathBidiContract {

    /** U+2066 LEFT-TO-RIGHT ISOLATE — opens a LTR directional scope. */
    const val LRI: Char = '⁦'

    /** U+2069 POP DIRECTIONAL ISOLATE — closes the innermost open isolate scope. */
    const val PDI: Char = '⁩'

    /**
     * Wraps [value] in Unicode LRI/PDI directional isolate marks so the string always
     * renders left-to-right regardless of the surrounding paragraph direction.
     *
     * Idempotent: if [value] already starts with [LRI] and ends with [PDI] it is
     * returned unchanged so callers need not track whether the string was wrapped.
     *
     * @param value A math display string (e.g. "15", "3.14", "1,234.56").
     * @return The wrapped string, or [value] unchanged if it was already wrapped.
     */
    fun wrapLtr(value: String): String {
        if (value.startsWith(LRI) && value.endsWith(PDI)) return value
        return "$LRI$value$PDI"
    }

    /**
     * Returns true if [value] carries the LRI/PDI wrapper, i.e. it was produced by
     * [wrapLtr] and is safe to display in an RTL paragraph without reversal.
     */
    fun isWrapped(value: String): Boolean =
        value.startsWith(LRI) && value.endsWith(PDI) && value.length >= 2

    /**
     * Strips the LRI/PDI wrapper added by [wrapLtr], recovering the raw math string.
     *
     * Use this when the string will be committed to an [InputConnection] (the editor
     * manages its own bidi rendering) or stored in a database (the wrapper is a
     * presentation hint, not content).
     *
     * No-op when [value] is not wrapped.
     */
    fun unwrap(value: String): String =
        if (isWrapped(value)) value.substring(1, value.length - 1) else value
}
