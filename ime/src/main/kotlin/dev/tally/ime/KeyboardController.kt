package dev.tally.ime

import android.os.SystemClock
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
     * Whether typed words are silently corrected when the user commits with Space.
     *
     * Initialised from [TallyPreferences.autocorrectEnabled] via [setAutocorrectEnabled].
     * When false the Space branch never auto-replaces — the typed word is committed verbatim
     * even if a high-confidence correction is available.
     */
    var autocorrectEnabled: Boolean = true
        private set

    /**
     * Whether committing a word via Space (or an applied autocorrect) appends a trailing space.
     *
     * Space always inserts a literal space when this is true (the default), matching the
     * existing behaviour. The flag exists so the autocorrect-on-space path and the word-tap
     * auto-space share one user-controllable setting; when false an applied autocorrect still
     * replaces the word but no extra space is added beyond the one the user pressed.
     */
    var autoSpaceEnabled: Boolean = true
        private set

    /**
     * Whether the shift key auto-capitalises at the start of a sentence.
     *
     * Initialised from [TallyPreferences.autoCapEnabled] via [setAutoCapEnabled]. When false the
     * shift machine never receives an auto-caps signal — neither the field's initialCapsMode on
     * entry nor the mid-session [onCapsMode] update — so shift stays exactly where the user left it.
     * A manual shift tap is unaffected and always works.
     */
    var autoCapEnabled: Boolean = true
        private set

    /**
     * Whether two spaces typed in quick succession collapse to ". " (period + space).
     *
     * Initialised from [TallyPreferences.doubleSpacePeriod] via [setDoubleSpacePeriod]. When false
     * the Space key always inserts a literal space, regardless of timing.
     */
    var doubleSpacePeriod: Boolean = true
        private set

    /**
     * Uptime (ms) of the most recently committed Space, or 0 when none is pending.
     *
     * Used by the double-space-to-period gesture: a second Space within
     * [DOUBLE_SPACE_WINDOW_MS] of the first — with a word character before the first space —
     * replaces the pair with ". ". Reset to 0 whenever a non-Space key is handled so a stale
     * timestamp from an earlier sentence can never trigger the replacement.
     */
    private var lastSpaceCommitMs: Long = 0L

    /**
     * The current top AUTOCORRECT candidate, surfaced from the suggestion strip.
     *
     * Set by [setAutocorrectCandidate] from [StripCoordinator]'s composed [StripState] on the
     * main thread — never by re-running the decoder here. Null when no correction is offered for
     * the in-progress word. Cleared whenever the composing word is committed or finished so a
     * stale candidate from a previous word can never be applied.
     */
    private var autocorrectCandidate: dev.tally.keyboard.engine.Suggestion? = null

    /**
     * Called on the main thread when the number row is toggled, with the new state.
     *
     * The IME service wires this to persist [TallyPreferences.numberRowEnabled] and
     * to re-sync the key plane rows.
     */
    var numberRowChangeListener: ((Boolean) -> Unit)? = null

    /**
     * Called on the main thread when the globe key is pressed.
     *
     * The IME service wires this to cycle to the next enabled subtype and reload the
     * active layout. Composing state is finished before the callback fires so the
     * new layout starts from a clean input state.
     */
    var globeListener: (() -> Unit)? = null

    /**
     * Called on the main thread when the voice key is pressed.
     *
     * The IME service wires this to start/stop the [OsVoiceInputEngine]. Composing
     * state is finished before the callback fires so the recognized text inserts at a
     * committed position rather than replacing a live composing region.
     */
    var voiceListener: (() -> Unit)? = null

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
     * Sets whether autocorrect-on-space is active. Read from [TallyPreferences] at session start
     * and on each field entry so a settings change is picked up without restarting the service.
     */
    fun setAutocorrectEnabled(enabled: Boolean) {
        autocorrectEnabled = enabled
    }

    /** Sets whether a trailing space is appended when a word is committed. */
    fun setAutoSpaceEnabled(enabled: Boolean) {
        autoSpaceEnabled = enabled
    }

    /**
     * Sets whether sentence-start auto-capitalisation is active. Read from [TallyPreferences] at
     * session start and on each field entry so a settings change is picked up without restarting.
     */
    fun setAutoCapEnabled(enabled: Boolean) {
        autoCapEnabled = enabled
    }

    /** Sets whether the double-space-to-period gesture is active. */
    fun setDoubleSpacePeriod(enabled: Boolean) {
        doubleSpacePeriod = enabled
    }

    /**
     * Receives the latest top AUTOCORRECT candidate from the suggestion strip.
     *
     * Called on the main thread whenever the strip recomputes. The candidate carries a
     * calibrated [dev.tally.keyboard.engine.Suggestion.confidence] used by the Space branch to
     * decide whether to auto-replace. Passing null clears any pending correction.
     */
    fun setAutocorrectCandidate(candidate: dev.tally.keyboard.engine.Suggestion?) {
        autocorrectCandidate = candidate
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
            if (autoCapEnabled && cls == InputType.TYPE_CLASS_TEXT && info.initialCapsMode != 0) {
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
        // When the user has opted out of auto-cap, the shift machine must never be driven by the
        // editor's caps signal — only by an explicit shift tap. Bail before touching the machine so
        // a sentence boundary does not silently raise shift.
        if (!autoCapEnabled) return
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
     * Move the cursor (or extend the selection) by [steps] positions.
     *
     * Triggered by the space-bar swipe gesture (T4.4). Each unit of [steps] is one
     * character position: negative = left, positive = right.
     *
     * When [select] is true (shift is currently latched or locked) the movement extends
     * the selection rather than moving the bare cursor. The composing region is finished
     * before movement so the cursor position is unambiguous and the editor is never left
     * with a dangling underline while selection is active.
     *
     * Movement is delivered via [KeyEvent] (DPAD_LEFT / DPAD_RIGHT with optional
     * SHIFT_LEFT meta) which is the canonical way for an IME to drive cursor movement
     * in a TYPE_NULL-safe manner across all editor implementations.
     */
    fun moveCursor(steps: Int, select: Boolean, ic: InputConnection) {
        if (steps == 0) return

        // Finish any composing region so the cursor sits at a committed position.
        if (composing.isComposing) {
            composing.finishComposing(ic)
        }

        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val metaState = if (select) KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON else 0
        val count = kotlin.math.abs(steps)
        val eventTime = SystemClock.uptimeMillis()

        repeat(count) {
            ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,   keyCode, 0, metaState))
        }
    }

    /**
     * Process a key emitted by the key plane.
     *
     * The IME service calls this from the main thread; it is the sole path from hardware/
     * virtual key to [InputConnection]. Factored out of the service so it can be driven in
     * unit tests with a fake [InputConnection].
     */
    fun handleKey(key: Key, ic: InputConnection, eventTimeMs: Long = SystemClock.uptimeMillis()) {
        // Any key other than Space breaks the double-space-to-period window: the two spaces must
        // be strictly consecutive. handleSpace records its own timestamp, so clearing here for
        // every other key (before dispatch) keeps the gesture from spanning intervening input.
        if (key.code != KeyCode.Space) {
            lastSpaceCommitMs = 0L
        }
        when (val code = key.code) {
            is KeyCode.Char -> handleChar(code.value, ic)
            KeyCode.Backspace -> composing.deleteCodePointsBefore(1, ic)
            KeyCode.Space -> handleSpace(ic, eventTimeMs)
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
            KeyCode.Globe -> {
                // Finish composing before switching layouts so the new layout starts clean.
                composing.finishComposing(ic)
                globeListener?.invoke()
            }
            KeyCode.Voice -> {
                // Finish composing before starting voice so the transcript inserts at a
                // committed cursor position rather than mid-word.
                composing.finishComposing(ic)
                voiceListener?.invoke()
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
     * Handle the Space key, applying autocorrect-on-space when appropriate.
     *
     * Mirrors the word-tap auto-space convention (TallyInputMethodService.commitWordCandidate):
     * a committed word is followed by a single space. When a high-confidence AUTOCORRECT
     * candidate exists for the in-progress word and that candidate differs from what the user
     * typed, the composing region is replaced by the candidate before the space is appended.
     *
     * The replacement is suppressed when [autocorrectEnabled] is false (user opted out), when no
     * candidate is available, when the candidate is not confident enough, or when the candidate
     * equals the typed word (nothing to correct). In every suppressed case a literal space is
     * committed, exactly as before this feature existed.
     *
     * The candidate is cleared after handling so a stale correction from this word can never be
     * applied to the next one; the strip will repopulate it on the following keystroke.
     *
     * The Space key always inserts the literal space the user pressed; [autoSpaceEnabled] gates
     * only the *auto-added* space on word-tap (see TallyInputMethodService.commitWordCandidate),
     * not the user's own keystroke here.
     *
     * Double-space-to-period ([doubleSpacePeriod]): when this Space lands within
     * [DOUBLE_SPACE_WINDOW_MS] of the previous one and the character just before the previous
     * space is a sentence-ending word character (letter or digit), the first space is rewritten
     * to '.' and a fresh space appended — yielding "word. ". This fires before the autocorrect
     * branch because the previous word was already committed by the first space, so there is no
     * composing region to correct here.
     *
     * [eventTimeMs] is the touch-up time of this Space (from [android.view.MotionEvent.getEventTime],
     * same clock as [SystemClock.uptimeMillis]). The window is measured between successive event
     * times so dispatch/IPC latency cannot stretch a real double-tap past the window.
     */
    private fun handleSpace(ic: InputConnection, eventTimeMs: Long) {
        // Measure the double-space window from the touch-up event time, not from when this
        // handler happens to run. The two share the same SystemClock.uptimeMillis() clock, but
        // the event time is captured the instant the finger lifts — before any dispatch/IPC
        // latency — so a genuine fast double-tap pairs correctly even when key delivery is slow
        // (the IME main thread, or a test/automation harness, can add hundreds of ms before this
        // code runs). Callers without a real touch event pass the current uptime by default.
        val now = eventTimeMs

        // Double-space-to-period: only when there is no in-progress word (the first space already
        // committed it), the window has not elapsed, and a word character precedes the trailing
        // space we are about to upgrade. Deleting that space and committing ". " keeps the editor's
        // single trailing space intact (one space in → ". " out, net one space added).
        if (doubleSpacePeriod &&
            !composing.isComposing &&
            lastSpaceCommitMs != 0L &&
            (now - lastSpaceCommitMs) <= DOUBLE_SPACE_WINDOW_MS &&
            precedingCharIsWord(ic)
        ) {
            composing.deleteCodePointsBefore(1, ic)  // remove the first space
            composing.commitText(". ", ic)
            lastSpaceCommitMs = 0L  // consume the pair so a third space starts fresh
            autocorrectCandidate = null
            return
        }

        val typed = composing.composingText
        val candidate = autocorrectCandidate
        val confidence = candidate?.confidence ?: 0f
        val shouldAutocorrect = autocorrectEnabled &&
            candidate != null &&
            typed.isNotEmpty() &&
            confidence >= AUTOCORRECT_CONFIDENCE_THRESHOLD &&
            !candidate.text.equals(typed, ignoreCase = true)

        if (shouldAutocorrect) {
            // Replace the composing word with the correction, then append the space separately so
            // the editor's undo history shows a clean replace followed by the space.
            composing.replaceComposingWith(candidate!!.text, ic)
            composing.commitText(" ", ic)
        } else {
            composing.commitText(" ", ic)
        }
        autocorrectCandidate = null
        // Record this space so a fast follow-up can upgrade it to a period.
        lastSpaceCommitMs = now
    }

    /**
     * True when the text before the cursor is "<word-char><single-space>" — i.e. exactly the
     * shape the first Space tap leaves behind.
     *
     * The cursor sits after the trailing space committed by the first tap, so the mirror reads
     * "…word " here. We require the last character to be that single space and the one before it
     * to be a letter or digit. This gates double-space-to-period to fire only after a real word
     * end — never after punctuation, a newline, two existing spaces (indenting), or an empty line.
     *
     * Reads the local [InputConnectionMirror] rather than the [InputConnection] so it stays
     * IPC-free; [ic] is accepted for symmetry with the other key handlers but not queried here.
     */
    private fun precedingCharIsWord(ic: InputConnection): Boolean {
        val before = mirror.textBefore
        if (before.length < 2) return false
        val trailing = before[before.length - 1]
        if (trailing != ' ') return false
        val beforeTrailing = before[before.length - 2]
        return beforeTrailing.isLetterOrDigit()
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
        // Drop any pending correction so it can never be applied to a different field's word.
        autocorrectCandidate = null
    }

    /** Expose composing state for test assertions. */
    internal fun composingText(): String = composing.composingText

    internal companion object {
        /**
         * Minimum calibrated [dev.tally.keyboard.engine.Suggestion.confidence] required before a
         * correction is applied automatically on Space. Set conservatively (≈0.85) because a
         * wrong silent autocorrect is more disruptive than a missed one; below this the user's
         * typed word is committed unchanged.
         */
        const val AUTOCORRECT_CONFIDENCE_THRESHOLD: Float = 0.85f

        /**
         * Maximum time between two Space taps for the pair to collapse to ". ". 1100 ms matches the
         * Android platform default (AOSP LatinIME `config_double_space_period_timeout`), so the
         * gesture pairs at the same cadence users learn on stock keyboards. The earlier 600 ms was
         * tighter than the platform and dropped genuine but unhurried double-taps; this is still
         * short enough that two intentional separate spaces (rare) are not merged.
         */
        const val DOUBLE_SPACE_WINDOW_MS: Long = 1100L
    }
}
