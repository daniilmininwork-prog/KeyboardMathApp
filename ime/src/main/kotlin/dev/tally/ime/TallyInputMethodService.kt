package dev.tally.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import dev.tally.glue.MathEvaluator
import dev.tally.glue.TallyPreferences
import dev.tally.math.Suggestion
import java.util.Locale

/**
 * Tally IME host.
 *
 * Wires the [TallyKeyboardView] to [InputConnection] and exposes [KeyboardHost] so that
 * [MathEvaluator] (feature-glue) can read text before the cursor and insert results without
 * depending on the concrete service class.
 *
 * Key-handling rules:
 * - Alpha characters commit via [InputConnection.commitText].
 * - Shift is one-shot: switches to upper-case for one key, then resets to lower-case.
 * - Backspace uses [InputConnection.deleteSurroundingText]; long-press repeats via the view.
 * - Enter prefers the field's IME action; falls back to inserting a newline.
 * - Mode-switch keys (123 / ABC / #+=) update the local [KeyboardState] only.
 * - Every text-changing key triggers debounced math evaluation via [MathEvaluator].
 */
class TallyInputMethodService : InputMethodService(), KeyboardHost {

    private var keyboardView: TallyKeyboardView? = null
    private var suggestionStrip: SuggestionStripView? = null
    private var state: KeyboardState = KeyboardState.ALPHA_LOWER
    private var evaluator: MathEvaluator? = null
    private var currentSuggestion: Suggestion? = null
    private lateinit var prefs: TallyPreferences

    // ── IME lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        prefs = TallyPreferences(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.keyboard_bg))
        }
        suggestionStrip = SuggestionStripView(this).also { strip ->
            strip.onSuggestionTapped = {
                currentSuggestion?.let { s ->
                    commitResult(s)
                    clearSuggestion()
                }
            }
            root.addView(strip)
        }
        keyboardView = TallyKeyboardView(this).also { root.addView(it) }
        keyboardView?.keyListener = TallyKeyboardView.KeyListener { key ->
            handleKey(key, currentInputConnection ?: return@KeyListener)
        }
        evaluator = MathEvaluator { suggestion ->
            currentSuggestion = suggestion
            val strip = suggestionStrip ?: return@MathEvaluator
            if (suggestion != null) {
                strip.chip.hapticEnabled = prefs.hapticsEnabled
                strip.showSuggestion(suggestion.display)
            } else {
                strip.clearSuggestion()
            }
        }
        applyState(state)
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        state = when (cls) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE -> KeyboardState.NUMERIC
            else -> KeyboardState.ALPHA_LOWER
        }
        applyState(state)
        clearSuggestion()
        // Evaluate immediately so fields with existing text show the chip on focus.
        requestEvaluate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        evaluator?.cancel()
        clearSuggestion()
    }

    // Re-evaluate whenever the cursor moves or text changes by any means (typing through
    // our keys, paste, adb inject, selection change). The debounce absorbs the double-fire
    // that occurs when our own commitText triggers this right after handleKey already did.
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        requestEvaluate()
    }

    private fun applyState(newState: KeyboardState) {
        state = newState
        val rows = when (newState) {
            KeyboardState.ALPHA_LOWER -> KeyboardLayout.ALPHA_LOWER
            KeyboardState.ALPHA_UPPER -> KeyboardLayout.ALPHA_UPPER
            KeyboardState.NUMERIC     -> KeyboardLayout.NUMERIC
            KeyboardState.SYMBOLS     -> KeyboardLayout.SYMBOLS
        }
        keyboardView?.setRows(rows)
    }

    /**
     * Inserts [s] according to the current insertion-mode preference.
     *
     * Append (default): commits the display value after the cursor.
     * Replace: deletes the detected expression span, then commits the value.
     */
    private fun commitResult(s: Suggestion) {
        val ic = currentInputConnection ?: return
        if (prefs.replaceExpression) {
            val expressionLen = s.span.last - s.span.first + 1
            ic.deleteSurroundingText(expressionLen, 0)
        }
        ic.commitText(s.display, 1)
    }

    /**
     * Core key-handling logic, separated so tests can drive it with a fake [InputConnection]
     * without needing a real IME session.
     */
    internal fun handleKey(key: Key, ic: InputConnection) {
        when (val code = key.code) {
            is KeyCode.Char -> {
                ic.commitText(code.value.toString(), 1)
                if (state == KeyboardState.ALPHA_UPPER) applyState(KeyboardState.ALPHA_LOWER)
                requestEvaluate()
            }
            KeyCode.Backspace -> { ic.deleteSurroundingText(1, 0); requestEvaluate() }
            KeyCode.Space     -> { ic.commitText(" ", 1); requestEvaluate() }
            KeyCode.Enter     -> { handleEnter(ic); requestEvaluate() }
            KeyCode.Shift -> applyState(
                if (state == KeyboardState.ALPHA_UPPER) KeyboardState.ALPHA_LOWER
                else KeyboardState.ALPHA_UPPER,
            )
            KeyCode.SwitchToNumeric -> applyState(KeyboardState.NUMERIC)
            KeyCode.SwitchToAlpha   -> applyState(KeyboardState.ALPHA_LOWER)
            KeyCode.SwitchToSymbols -> applyState(KeyboardState.SYMBOLS)
        }
    }

    private fun requestEvaluate() {
        if (!prefs.enabled) {
            clearSuggestion()
            return
        }
        val text = getTextBeforeCursor(MathEvaluator.MAX_TEXT_LENGTH)?.toString() ?: return
        val locale = prefs.localeOverride
            .takeIf { it.isNotEmpty() }
            ?.let { Locale.forLanguageTag(it) }
            ?: Locale.getDefault()
        evaluator?.onTextChanged(
            text        = text,
            locale      = locale,
            percentMode = prefs.percentMode,
            precision   = prefs.precision,
        )
    }

    private fun handleEnter(ic: InputConnection) {
        val action = currentInputEditorInfo
            ?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED

        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // ── KeyboardHost ─────────────────────────────────────────────────────────

    override fun getTextBeforeCursor(maxLength: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(maxLength, 0)

    override fun insertResult(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun clearSuggestion() {
        currentSuggestion = null
        suggestionStrip?.clearSuggestion()
    }
}
