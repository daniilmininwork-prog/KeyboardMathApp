package dev.tally.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.tally.keyboard.engine.ShiftState
import dev.tally.keyboard.engine.ShiftStateMachine

/**
 * Central coordinator for the keyboard session.
 *
 * Created once in [TallyInputMethodService.onCreateInputView] and *reconfigured* — never
 * recreated — in [TallyInputMethodService.onStartInputView]. This separation keeps per-field
 * logic out of view inflation and avoids the cost of rebuilding the full view hierarchy on
 * every field focus.
 *
 * ## Shift/caps state machine (T1.7)
 *
 * Shift is managed by [ShiftStateMachine] (pure JVM, in keyboard-engine):
 *   - Tri-state: OFF → SHIFTED → LOCKED.
 *   - SHIFTED is one-shot: reverts to OFF after the next character key.
 *   - LOCKED is sustained: stays until the user taps shift again.
 *   - Double-tap (two shift presses within 300 ms) promotes SHIFTED → LOCKED.
 *   - Auto-capitalisation reads [EditorInfo.initialCapsMode] on field entry and updates
 *     via [onCapsMode] when the cursor moves to a sentence start.
 *
 * ## Composing-text pipeline (T1.5)
 *
 * Character input flows through [ComposingTextManager]:
 *   - Alpha keystrokes accumulate in the composing region via [setComposingText].
 *   - Space/enter/mode-switch calls [finishComposingText] before the commit.
 *   - Backspace uses [deleteSurroundingTextInCodePoints] (emoji-safe).
 *   - All compound operations are wrapped in balanced batch edits.
 *
 * ## Number row (T1.10)
 *
 * [numberRowEnabled] controls whether the optional digit row (1–9, 0) is prepended to the
 * alphabetic key grid. The flag is set from [TallyPreferences] at session start and toggled
 * by [KeyCode.ToggleNumberRow]. Callers observe changes via [numberRowChangeListener] and
 * are responsible for persisting the flag back to preferences.
 */
internal class KeyboardController {

    private var keyboardState: KeyboardState = KeyboardState.ALPHA_LOWER

    /**
     * Whether the optional number row is currently shown above the QWERTY rows.
     *
     * Initialised from [TallyPreferences.numberRowEnabled] via [setNumberRowEnabled].
     * Flipped by [KeyCode.ToggleNumberRow]; the IME service listens via [numberRowChangeListener]
     * to persist the new value.
     */
    var numberRowEnabled: Boolean = false
        private set

    /**
     * Called on the main thread when the number row is toggled, with the new state.
     *
     * The IME service wires this to persist [TallyPreferences.numberRowEnabled] and
     * to re-sync the key plane rows.
     */
    var numberRowChangeListener: ((Boolean) -> Unit)? = null

    // Populated once the IME session has an active InputConnection.
    private var inputConnectionProvider: (() -> InputConnection?)? = null

    /** The [EditorInfo] for the currently active field; used to determine the Enter action. */
    private var currentEditorInfo: EditorInfo? = null

    val mirror = InputConnectionMirror()
    private val composing = ComposingTextManager(mirror)

    /** The tri-state shift machine. Exposed for test inspection. */
    internal val shiftMachine = ShiftStateMachine()

    fun setInputConnectionProvider(provider: () -> InputConnection?) {
        inputConnectionProvider = provider
    }

    /**
     * Initialises the number row flag from the persisted preference.
     *
     * Called from [TallyInputMethodService.onCreateInputView] so the controller
     * reflects the user's saved preference from the first keystroke.
     */
    fun setNumberRowEnabled(enabled: Boolean) {
        numberRowEnabled = enabled
    }

    /**
     * Re-initialise per-field state from [info].
     *
     * Called from [TallyInputMethodService.onStartInputView] with [restarting] so that
     * user-visible state (e.g. typed characters still visible) is not wiped on a soft restart
     * of the same field.
     *
     * On a fresh field focus the composing region is cleared and the keyboard state is derived
     * from [EditorInfo.inputType] plus [EditorInfo.initialCapsMode] for autocaps. The mirror
     * is seeded from the new field's [InputConnection].
     */
    fun configure(info: EditorInfo, restarting: Boolean, ic: InputConnection?) {
        currentEditorInfo = info
        if (!restarting) {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            keyboardState = when (cls) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE -> KeyboardState.NUMERIC
                else -> KeyboardState.ALPHA_LOWER
            }

            // Reset the shift machine and apply the field's initial caps mode.
            // initialCapsMode is non-zero when the editor wants a capital at the cursor position
            // (e.g. TYPE_TEXT_FLAG_CAP_SENTENCES at the start of a field).
            shiftMachine.forceState(ShiftState.OFF)
            if (cls == InputType.TYPE_CLASS_TEXT && info.initialCapsMode != 0) {
                shiftMachine.applyAutoCaps(info.initialCapsMode)
            }

            // Sync keyboard layer with shift state.
            keyboardState = resolveKeyboardState()
            composing.resetWithoutIc()
        }
        mirror.seed(ic, editorPackageName = info.packageName)
    }

    /**
     * Overload retained for backward-compat with existing unit tests that do not supply an [ic].
     * Production code calls the three-argument form.
     */
    fun configure(info: EditorInfo, restarting: Boolean) = configure(info, restarting, null)

    fun currentState(): KeyboardState = keyboardState

    /** Exposes the shift sub-state for rendering decisions (latched vs locked visual). */
    fun currentShiftState(): ShiftState = shiftMachine.state

    /**
     * Applies a new auto-capitalisation signal from the editor.
     *
     * Called from [TallyInputMethodService.onUpdateSelection] (via the composing manager's
     * post-commit hook) when the cursor moves to a position with different caps requirements.
     * The shift machine decides whether to apply or clear the SHIFTED state based on whether
     * the machine is currently in an auto-capped state or untouched by the user.
     */
    fun onCapsMode(capsFlags: Int) {
        shiftMachine.applyAutoCaps(capsFlags)
        keyboardState = resolveKeyboardState()
    }

    /**
     * Delete the word before the cursor in a single batch edit.
     *
     * Triggered by the swipe-left gesture on the backspace key (T1.9). Delegates to
     * [ComposingTextManager.deleteWordBefore] which handles composing-region teardown,
     * batch-edit wrapping, and mirror updates.
     */
    fun deleteWordBefore(ic: InputConnection) {
        composing.deleteWordBefore(ic)
    }

    /**
     * Process a key emitted by the key plane.
     *
     * The IME service calls this from the main thread; it is the sole path from hardware/
     * virtual key to [InputConnection]. Factored out of the service so it can be driven in
     * unit tests with a fake [InputConnection].
     */
    fun handleKey(key: Key, ic: InputConnection) {
        when (val code = key.code) {
            is KeyCode.Char -> handleChar(code.value, ic)
            KeyCode.Backspace -> composing.deleteCodePointsBefore(1, ic)
            KeyCode.Space -> composing.commitText(" ", ic)
            KeyCode.Enter -> {
                composing.finishComposing(ic)
                dispatchEnter(ic)
            }
            KeyCode.Shift -> {
                shiftMachine.onShiftPressed()
                keyboardState = resolveKeyboardState()
            }
            KeyCode.SwitchToNumeric -> {
                composing.finishComposing(ic)
                keyboardState = KeyboardState.NUMERIC
            }
            KeyCode.SwitchToAlpha -> {
                composing.finishComposing(ic)
                // Restore lower unless the shift machine currently requires upper.
                keyboardState = resolveKeyboardState(forceAlpha = true)
            }
            KeyCode.SwitchToSymbols -> {
                composing.finishComposing(ic)
                keyboardState = KeyboardState.SYMBOLS
            }
            KeyCode.ToggleNumberRow -> {
                numberRowEnabled = !numberRowEnabled
                numberRowChangeListener?.invoke(numberRowEnabled)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleChar(value: Char, ic: InputConnection) {
        val isUpper = shiftMachine.state != ShiftState.OFF
        val ch = if (isUpper) value.uppercaseChar() else value

        when (keyboardState) {
            KeyboardState.ALPHA_LOWER,
            KeyboardState.ALPHA_UPPER -> {
                composing.appendToComposing(ch, ic)
                shiftMachine.onCharTyped()
                keyboardState = resolveKeyboardState()
            }
            KeyboardState.NUMERIC,
            KeyboardState.SYMBOLS -> {
                composing.commitText(ch.toString(), ic)
            }
        }
    }

    /**
     * Derives the alpha keyboard layer from the current shift machine state.
     *
     * When [forceAlpha] is true the result is always one of the ALPHA variants (used when
     * returning from a numeric/symbol layer whose shift machine state should be respected).
     */
    private fun resolveKeyboardState(forceAlpha: Boolean = false): KeyboardState {
        return when (keyboardState) {
            KeyboardState.NUMERIC,
            KeyboardState.SYMBOLS -> if (forceAlpha) resolveAlphaState() else keyboardState
            else -> resolveAlphaState()
        }
    }

    private fun resolveAlphaState(): KeyboardState =
        if (shiftMachine.state != ShiftState.OFF) KeyboardState.ALPHA_UPPER
        else KeyboardState.ALPHA_LOWER

    /**
     * Dispatches the Enter key to the editor.
     *
     * If the field has a specific IME action (Send, Search, Next, Done, Go) that action is
     * performed. For fields whose action is NONE or UNSPECIFIED — including multi-line text
     * fields — a literal newline is injected via [KeyEvent] so the field actually receives a
     * line break. Always sending IME_ACTION_UNSPECIFIED is incorrect: many editors treat it as
     * a no-op, so the Enter key silently does nothing in notes apps and multi-line fields.
     */
    private fun dispatchEnter(ic: InputConnection) {
        val imeOptions = currentEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            // Multi-line field or no specific action: send a literal newline via KeyEvents
            // so the editor inserts a line break.
            val eventTime = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        }
    }

    /**
     * Tear down composing state when the input view is finishing.
     *
     * Calls [ComposingTextManager.resetWithoutIc] to clear the composing buffer and reset
     * [batchDepth] without touching the [InputConnection] (which may already be invalidated
     * when [TallyInputMethodService.onFinishInputView] fires). If [ic] is non-null the
     * composing region is also finished on the live editor before teardown, so the editor
     * never gets stuck with a dangling underline.
     */
    fun teardown(ic: InputConnection?) {
        if (ic != null && composing.isComposing) {
            composing.finishComposing(ic)
        }
        composing.resetWithoutIc()
    }

    /** Expose composing state for test assertions. */
    internal fun composingText(): String = composing.composingText
}
