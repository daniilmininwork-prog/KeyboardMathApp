package dev.tally.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tally.design.MathResultChip
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind

/**
 * Suggestion strip that hosts a [MathResultChip] in a reserved leading slot and up to
 * [WORD_SLOT_COUNT] word-candidate [TextView]s in trailing slots.
 *
 * ## Slot layout
 *
 *  ┌────────────────────────────────────────────────────────────┐
 *  │ [MathResultChip] │ word-1 │ word-2 │ word-3               │
 *  └────────────────────────────────────────────────────────────┘
 *
 *  - Math chip occupies its own fixed-width cell to the left; it is never displaced.
 *  - When math has no result the cell collapses (GONE) and the word slots expand.
 *  - When suggestions are disabled all slots are empty.
 *
 * ## Threading
 *
 *  All mutations must be called on the main thread.
 *
 * ## Backward-compat (legacy math-only callers)
 *
 *  [showSuggestion] and [clearSuggestion] map to [bind] calls for callers that have not
 *  yet been updated to supply a full [StripState].  The [onSuggestionTapped] callback
 *  covers the math-only tap for those callers; multi-source callers use the callbacks
 *  in [bind] directly.
 */
internal class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    // ── Legacy single-source API ──────────────────────────────────────────────

    /** Callback for the math chip tap (legacy / single-source consumers). */
    var onSuggestionTapped: (() -> Unit)? = null

    /** The currently shown math suggestion text, null when the strip is empty. */
    internal var suggestion: String? = null
        private set

    // ── Views ─────────────────────────────────────────────────────────────────

    internal val chip = MathResultChip(context).also { chip ->
        chip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        chip.onTap = { onSuggestionTapped?.invoke() }
    }

    private val wordSlots: List<TextView> = List(WORD_SLOT_COUNT) { idx ->
        TextView(context).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, WORD_TEXT_SIZE_SP)
            isFocusable  = true
            isClickable  = false
            isSingleLine = true
            ellipsize    = android.text.TextUtils.TruncateAt.END
            contentDescription = null
            tag = idx
        }
    }

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    init {
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildLayout()
        applyTheme()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Binds a [StripState] to the strip.
     *
     * Math chip is shown when [state.mathSuggestion] is non-null; word slots fill from
     * [state.wordCandidates].  Taps route to [onWordTap] / [onMathTap] respectively.
     *
     * Must be called on the main thread.
     */
    fun bind(
        state: StripState,
        onWordTap: (Suggestion) -> Unit = {},
        onMathTap: () -> Unit = {},
    ) {
        // Math slot.
        val math = state.mathSuggestion
        if (math != null) {
            suggestion = math.text
            chip.hapticEnabled = true
            chip.onTap = onMathTap
            chip.show(math.text)
            chip.visibility = View.VISIBLE
            contentDescription = context.getString(R.string.chip_insert_description, math.text)
            isClickable = true
        } else {
            suggestion = null
            chip.dismiss()
            chip.visibility = View.GONE
            contentDescription = null
            isClickable = false
        }

        // Word slots: fill available candidates, hide the rest.
        val candidates = state.wordCandidates
        wordSlots.forEachIndexed { idx, tv ->
            val candidate = candidates.getOrNull(idx)
            if (candidate != null) {
                tv.text = candidate.text
                tv.isClickable = true
                tv.setOnClickListener { onWordTap(candidate) }
                tv.contentDescription = context.getString(
                    R.string.word_candidate_description, candidate.text
                )
                tv.visibility = View.VISIBLE
                applyWordSlotStyle(tv, candidate.kind == SuggestionKind.AUTOCORRECT && idx == 0)
            } else {
                tv.text = null
                tv.isClickable = false
                tv.setOnClickListener(null)
                tv.contentDescription = null
                tv.visibility = View.GONE
            }
        }
    }

    /**
     * Shows a math suggestion in the strip (legacy single-source API).
     *
     * Equivalent to [bind] with [state.mathSuggestion] set and no word candidates.
     */
    fun showSuggestion(display: String) {
        val mathSuggestion = Suggestion(
            kind  = SuggestionKind.MATH,
            text  = display,
            score = 0f,
        )
        bind(
            state     = StripState(mathSuggestion = mathSuggestion, wordCandidates = emptyList()),
            onMathTap = { onSuggestionTapped?.invoke() },
        )
    }

    /**
     * Clears all suggestions from the strip (legacy single-source API).
     */
    fun clearSuggestion() {
        bind(state = StripState.EMPTY)
        suggestion = null
    }

    // ── Configuration changes ─────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    // ── Measurement ───────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resources.getDimensionPixelSize(R.dimen.suggestion_strip_height)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    // ── Legacy click ──────────────────────────────────────────────────────────

    override fun performClick(): Boolean {
        super.performClick()
        if (suggestion != null) onSuggestionTapped?.invoke()
        return true
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildLayout() {
        container.removeAllViews()

        // Math chip cell: fixed fraction of the strip width.
        val chipParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, MATH_SLOT_WEIGHT)
        chipParams.gravity = Gravity.CENTER_VERTICAL
        container.addView(chip, chipParams)
        chip.visibility = View.GONE

        // Word candidate cells: share remaining width equally.
        val wordWeight = (TOTAL_WEIGHT - MATH_SLOT_WEIGHT) / WORD_SLOT_COUNT.toFloat()
        wordSlots.forEach { tv ->
            val p = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, wordWeight)
            p.gravity = Gravity.CENTER_VERTICAL
            tv.visibility = View.GONE
            container.addView(tv, p)
        }
    }

    private fun applyTheme() {
        setBackgroundColor(context.getColor(R.color.suggestion_strip_bg))
        wordSlots.forEach { tv ->
            tv.setTextColor(context.getColor(R.color.suggestion_word_text))
        }
    }

    /**
     * Applies visual emphasis to word slots.
     *
     * The first AUTOCORRECT candidate gets a slightly bolder style to signal to the user
     * that pressing space will commit it automatically (matching Gboard / AOSP convention).
     */
    private fun applyWordSlotStyle(tv: TextView, isBold: Boolean) {
        tv.setTypeface(
            null,
            if (isBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
    }

    private companion object {
        const val WORD_SLOT_COUNT    = 3
        const val WORD_TEXT_SIZE_SP  = 15f
        /** Weight share for the math chip. The remaining weight is split among word slots. */
        const val MATH_SLOT_WEIGHT   = 1f
        const val TOTAL_WEIGHT       = 4f   // 1 math + 3 word
    }
}
