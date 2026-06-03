package dev.tally.ime

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.text.style.CharacterStyle
import android.text.TextPaint

/**
 * Builds the styled composing/committed text used to flag a misspelled word with a red
 * spell-check underline (Stage 5).
 *
 * The span is applied to the composing region via [InputConnection.setComposingText]; editors
 * render the span on the in-progress word so the underline appears live as the user types. The
 * underline is a solid red line drawn under the word — chosen over a wavy line because [UnderlineSpan]
 * is the only underline primitive every editor honours, and we recolor it red with a paint hook so
 * it reads as a spell-check flag rather than ordinary composing-text styling.
 *
 * Everything here is presentation-only: when the word is not flagged the plain [CharSequence] is
 * returned unchanged so a known word shows the editor's normal composing underline (or none).
 */
internal object SpellUnderline {

    /**
     * Spell-check red. A saturated red that stays legible on both light and dark editor
     * backgrounds; matches the conventional "misspelled" affordance users expect from a keyboard.
     */
    private const val UNDERLINE_COLOR: Int = 0xFFE53935.toInt() // Material red 600

    /**
     * Returns [text] styled with a red underline when [misspelled] is true, otherwise [text]
     * unchanged.
     *
     * The styled result is a [SpannableString] carrying a [RedUnderlineSpan] over the whole word.
     * Returning the same [text] reference when not misspelled avoids allocating a Spannable for the
     * common (correctly-spelled) keystroke path.
     */
    fun decorate(text: CharSequence, misspelled: Boolean): CharSequence {
        if (!misspelled || text.isEmpty()) return text
        return SpannableString(text).apply {
            setSpan(RedUnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * An [UnderlineSpan] subclass that recolors the underline red.
     *
     * [UnderlineSpan] itself draws using the current text colour. Overriding [updateDrawState] to
     * set [TextPaint.underlineColor]-equivalent behaviour is not portable across API levels, so we
     * instead set the paint's colour for the underline via the public underline-color setter when
     * available and fall back to leaving the platform underline in place otherwise. Subclassing keeps
     * the span identifiable (for removal / testing) and lets the colour live in one place.
     */
    internal class RedUnderlineSpan : UnderlineSpan() {
        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = true
            // setUnderlineText(color, thickness) sets a dedicated underline colour distinct from the
            // glyph colour on API 29+; on older APIs the platform draws the underline in the text
            // colour, which is acceptable degradation (the word is still underlined).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ds.underlineColor = UNDERLINE_COLOR
                ds.underlineThickness = ds.density * UNDERLINE_THICKNESS_DP
            }
        }
    }

    /** Underline thickness in dp for the red spell-check line on API 29+. */
    private const val UNDERLINE_THICKNESS_DP: Float = 1.5f

    /**
     * True when [text] carries at least one [RedUnderlineSpan].
     *
     * Used by unit tests to assert that an unknown word was decorated and a known word was not,
     * without depending on the on-screen rendering.
     */
    fun isFlagged(text: CharSequence): Boolean {
        if (text !is Spanned) return false
        return text.getSpans(0, text.length, CharacterStyle::class.java)
            .any { it is RedUnderlineSpan }
    }
}
