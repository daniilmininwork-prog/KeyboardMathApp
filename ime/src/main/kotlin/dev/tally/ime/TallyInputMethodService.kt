package dev.tally.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import dev.tally.glue.MathEvaluator
import dev.tally.glue.TallyPreferences
import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.KeyboardHeightPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.math.Suggestion as MathSuggestion
import dev.tally.prediction.DictionaryLoader
import dev.tally.prediction.DecoderFactory
import dev.tally.prediction.DecoderStack
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tally IME entry point.
 *
 * Follows the canonical IME lifecycle contract (03-ime-architecture-v2.md §3.2):
 *
 *   onCreate              → one-time global resources
 *   onInitializeInterface → rebuild layout descriptors on config change (not per field)
 *   onCreateInputView     → inflate the view hierarchy once; cached by the framework
 *   onStartInputView      → per-field: read EditorInfo, reconfigure [KeyboardController],
 *                           seed [InputConnectionMirror] from the new field
 *   onFinishInputView     → dismiss popups/timers; finish composing
 *   onComputeInsets       → report stable content insets so host animations stay smooth
 *   onEvaluateFullscreenMode → always false; we never steal the whole screen
 *
 * The math suggestion seam is preserved via [KeyboardHost] so [MathEvaluator] in feature-glue
 * never depends on the concrete service class.
 *
 * ## Composing-text pipeline (T1.5)
 *
 * Character input flows through [ComposingTextManager] inside [KeyboardController]:
 *   - Alpha keystrokes accumulate in the composing region via [InputConnection.setComposingText].
 *   - Space/enter/mode-switch calls [InputConnection.finishComposingText] before the commit.
 *   - Backspace uses [InputConnection.deleteSurroundingTextInCodePoints] (emoji-safe).
 *   - All compound operations are wrapped in balanced batch edits.
 *
 * ## Suggestion strip (T2.3)
 *
 * [StripCoordinator] manages multiple [SuggestionSource]s:
 *   - Math: [MathEvaluator] delivers results via [StripCoordinator.updateMath].
 *   - Word: [WordPredictorImpl] is queried by the coordinator on the bg executor.
 *   - Strip composition: math chip occupies the leading reserved slot; word candidates
 *     fill trailing slots. Updates are coalesced to ≤ 1 per input event.
 *   - Tapping a word candidate commits via a composing-region-aware batch edit.
 *
 * ## Re-entrancy guard
 *
 * [onUpdateSelection] checks [InputConnectionMirror.isMidBatchEdit] before re-triggering
 * evaluation. This prevents the double-fire that would otherwise occur when our own commitText
 * causes the platform to call back into onUpdateSelection.
 */
class TallyInputMethodService : InputMethodService(), KeyboardHost {

    // ── Singletons (one per process lifetime) ────────────────────────────────

    private lateinit var prefs: TallyPreferences
    private lateinit var feedback: KeyFeedback

    /**
     * Single [KeyboardController] instance.
     *
     * A lazy initializer means tests that call [handleKey] directly (before [onCreateInputView])
     * still get a functional controller without needing to start the IME session. In production
     * the lazy is forced by [onCreateInputView]; the controller is then *reconfigured* — never
     * recreated — in [onStartInputView].
     */
    private val controller: KeyboardController by lazy {
        KeyboardController().also { it.setInputConnectionProvider { currentInputConnection } }
    }

    // ── View references ───────────────────────────────────────────────────────

    private var keyPlane: KeyPlaneView? = null
    private var suggestionStrip: SuggestionStripView? = null
    private var rootView: android.widget.LinearLayout? = null

    // ── Math suggestion (T2.4) ───────────────────────────────────────────────

    /**
     * Formal adapter over [MathEvaluator] (03 §4.1, T2.4).
     *
     * Created once at process start; holds the latest [math.Suggestion] with its
     * [span] (for expression-replace) and [exactValue] (for full-precision insert).
     * The evaluator's debounce/bg machinery runs independently; [mathSource] just
     * receives the result and the coordinator is notified via [StripCoordinator.updateMath].
     */
    private val mathSource = MathSuggestionSource()
    private var evaluator: MathEvaluator? = null

    /**
     * Privacy contract for the currently active field.
     *
     * Derived once per field in [onStartInputView] via [FieldPolicyFactory] and propagated to
     * every surface that must respect field sensitivity. Starts at [FieldPolicy.DEFAULT_PRIVATE]
     * so the brief window between IME attach and the first [onStartInputView] is fail-closed.
     */
    private var fieldPolicy: FieldPolicy = FieldPolicy.DEFAULT_PRIVATE

    // ── Prediction / suggestion strip (T2.3) ─────────────────────────────────

    /**
     * Assembled prediction stack loaded once from the bundled dictionary asset.
     *
     * Null until the background load completes. Sources that depend on it (the word
     * predictor) return empty lists until then, so the strip shows only math or nothing
     * during the brief load window.
     */
    private var decoderStack: DecoderStack? = null

    /** Background executor for dictionary loading and source queries. */
    private val bgExecutor = Executors.newSingleThreadExecutor()

    /**
     * Coordinates multi-source strip updates.
     *
     * Created alongside the view hierarchy in [onCreateInputView] so the strip is
     * always wired even before the dictionary finishes loading.
     */
    private var stripCoordinator: StripCoordinator? = null

    // ── IME lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = TallyPreferences(this)
        feedback = KeyFeedback(this)

        // Load the dictionary on the bg executor so the main thread is never blocked.
        bgExecutor.execute {
            val dict = try {
                DictionaryLoader(assets).load()
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Failed to read prediction dictionary asset; word suggestions disabled", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error reading prediction dictionary; word suggestions disabled", e)
                null
            } ?: return@execute

            try {
                val stack = DecoderFactory.create(dict)
                decoderStack = stack
                // Wire the word predictor into the coordinator now that it is ready.
                stripCoordinator?.setWordSource(stack.wordPredictor)
            } catch (e: IllegalArgumentException) {
                // BeamDecoder / WordPredictorImpl constructors throw IllegalArgumentException
                // for invalid dictionary parameters (e.g. empty word list, bad beam width).
                // Narrow catch: programming errors (NullPointerException, etc.) propagate
                // to the executor's uncaught-exception handler so they are never silent.
                val errorId = System.nanoTime()
                Log.e(TAG, "DecoderFactory.create failed [errorId=$errorId]; word suggestions disabled", e)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    Log.d(TAG, "Suggestion degradation: keyboard will operate without word predictions " +
                        "(errorId=$errorId). Check logcat for the full stack trace above.")
                }
            }
        }
    }

    /**
     * Called when the system configuration changes (rotation, locale, font scale).
     *
     * Layout geometry that depends on screen dimensions is recomputed here so that field focus
     * does not pay for a rebuild on every [onStartInputView]. The key plane is asked to
     * re-measure itself so the row heights adjust immediately to the new orientation.
     */
    override fun onInitializeInterface() {
        super.onInitializeInterface()
        keyPlane?.requestLayout()
    }

    /**
     * Responds to device configuration changes that reach the IME process.
     *
     * Android reroutes orientation changes to IMEs through [onInitializeInterface] rather
     * than through [onConfigurationChanged] in most cases; this override catches any
     * residual configuration events (e.g. font scale, display density) that the framework
     * does not collapse into the interface callback. The key plane is invalidated and
     * re-measured here as a safety net for those cases.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyPlane?.requestLayout()
        rootView?.setBackgroundColor(getColor(R.color.keyboard_bg))
    }

    // Lazily initialised so it has access to a valid Context after onCreate().
    private val layoutLoader: LayoutLoader by lazy { LayoutLoader(this) }

    /**
     * Inflates the keyboard view hierarchy exactly once; the framework caches and reuses the
     * returned view across field transitions.
     *
     * Accessing [controller] here forces the lazy and wires the [InputConnection] provider.
     */
    override fun onCreateInputView(): View {
        controller.setInputConnectionProvider { currentInputConnection }

        controller.setNumberRowEnabled(prefs.numberRowEnabled)
        controller.numberRowChangeListener = { enabled ->
            prefs.numberRowEnabled = enabled
            syncKeyPlaneRows()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.keyboard_bg))
        }
        rootView = root

        // Build the strip coordinator before inflating the strip view so the callback is ready.
        // A no-op word source is used until the dictionary load completes; it is replaced by
        // StripCoordinator.setWordSource once the bg load fires.
        val coordinator = StripCoordinator(
            wordSource  = { _, _ -> emptyList() },
            bgExecutor  = bgExecutor,
            onUpdate    = { state -> bindStripState(state) },
        )
        stripCoordinator = coordinator

        // If the dictionary was already loaded before the view was created (e.g. fast device or
        // re-creation after rotation), wire the real predictor immediately.
        decoderStack?.wordPredictor?.let { coordinator.setWordSource(it) }

        suggestionStrip = SuggestionStripView(this).also { strip ->
            // Legacy single-source tap path kept for backward compatibility.
            strip.onSuggestionTapped = {
                commitMathFromSource()
                stripCoordinator?.updateMath(null)
            }
            root.addView(strip)
        }

        keyPlane = KeyPlaneView(this).also { plane ->
            plane.keyListener = { key ->
                val ic = currentInputConnection
                if (ic != null) handleKey(key, ic)
            }
            plane.keyDownFeedbackListener = {
                feedback.hapticsEnabled = prefs.hapticsEnabled
                feedback.soundEnabled   = prefs.soundEnabled
                feedback.onKeyDown(plane)
            }
            plane.backspaceRepeatListener = backspaceRepeat@{
                val ic = currentInputConnection ?: return@backspaceRepeat
                controller.handleKey(Key(KeyCode.Backspace, ""), ic)
                requestStripUpdate()
                syncKeyPlaneRows()
            }
            plane.wordDeleteListener = wordDelete@{
                val ic = currentInputConnection ?: return@wordDelete
                controller.deleteWordBefore(ic)
                requestStripUpdate()
                syncKeyPlaneRows()
            }
            root.addView(plane)
        }

        evaluator = MathEvaluator { mathResult ->
            mathSource.onResult(mathResult)
            coordinator.updateMath(mathResult)
            val strip = suggestionStrip ?: return@MathEvaluator
            strip.chip.hapticEnabled = prefs.hapticsEnabled
        }

        return root
    }

    /**
     * Per-field entry point.
     *
     * Reconfigures the [KeyboardController] from [EditorInfo]. When [restarting] is true the
     * field was briefly hidden and is now shown again; user-visible state (typed characters
     * still on screen) is preserved — the controller skips mode reset in that case.
     *
     * The [InputConnectionMirror] is seeded here from the new field's [InputConnection] so that
     * subsequent reads use the local mirror rather than blocking IPC calls. This is the field-
     * entry read; all subsequent updates come through [onUpdateSelection].
     *
     * Key-preview suppression is derived here so the key plane never shows typed glyphs in
     * popup bubbles for password or other masked input fields (03 §3.4, 03 §9).
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        fieldPolicy = FieldPolicyFactory.from(info)

        controller.configure(info, restarting, currentInputConnection)

        keyPlane?.previewMasked = fieldPolicy.previewMasked
        syncKeyPlaneRows()

        stripCoordinator?.clear()
        mathSource.clear()
        evaluator?.cancel()

        requestStripUpdate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        evaluator?.cancel()
        mathSource.clear()
        stripCoordinator?.clear()
        controller.teardown(currentInputConnection)
    }

    /**
     * Prevents landscape extract-mode from stealing the full screen.
     *
     * The default returns true in landscape, showing a full-screen edit field that breaks
     * edge-to-edge apps and is the wrong UX for a general-purpose keyboard.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Reports stable content and visible insets so host [WindowInsetsAnimation] callbacks
     * stay smooth and content does not slide under the keyboard.
     *
     * The total keyboard height is the suggestion strip height plus the key plane height.
     * Both are derived from screen metrics — not from the measured view — so the value is
     * available before the first layout pass. The content inset (the region the host must
     * scroll its content above) equals the full keyboard height. The visible inset is the
     * same value; the keyboard has no transparent chrome at the bottom.
     *
     * The height must be finalized here and must not change while a [WindowInsetsAnimation]
     * is running on the host side; [onInitializeInterface] and [onConfigurationChanged] both
     * trigger [KeyPlaneView.requestLayout] before this path is reached on the new config so
     * the value is stable.
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        super.onComputeInsets(outInsets)
        outInsets ?: return

        val dm = resources.displayMetrics
        val rowCount = (keyPlane?.currentRows?.size ?: DEFAULT_ROW_COUNT).coerceAtLeast(1)

        val keyPlaneHeight = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            density        = dm.density,
            rowCount       = rowCount,
        )
        val stripHeight = resources.getDimensionPixelSize(R.dimen.suggestion_strip_height)
        val totalHeight = keyPlaneHeight + stripHeight

        outInsets.contentTopInsets = totalHeight
        outInsets.visibleTopInsets = totalHeight
        outInsets.touchableInsets  = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
    }

    /**
     * Re-evaluates math and queries word sources when the cursor moves by any means:
     * our own commits, paste, adb.
     *
     * **Re-entrancy guard:** if the mirror reports that we are mid-batch-edit this notification
     * was triggered by our own composing-text commit. Skipping the re-evaluation here prevents:
     *   - A redundant evaluation that would produce a stale chip flicker.
     *   - Recursive calls into [requestStripUpdate] while [ComposingTextManager.withBatchEdit]
     *     still holds the batch-edit lock.
     *
     * When [reconcile] returns true the change was external (paste, autofill, direct editor
     * manipulation); the mirror is already refreshed and evaluation should proceed.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        val shouldEvaluate = controller.mirror.reconcile(newSelStart, newSelEnd, currentInputConnection)
        if (shouldEvaluate) requestStripUpdate()
    }

    // ── Key dispatch ──────────────────────────────────────────────────────────

    /**
     * Routes a key from the key plane to the active [InputConnection].
     *
     * Space auto-commits a visible math result before inserting the space character
     * (matching the Gboard convention: space = "accept the leading strip suggestion").
     * The commit and the space are separate [InputConnection] operations so the undo
     * history is clean.
     *
     * Kept `internal` so instrumentation tests can drive it without a live IME session.
     */
    internal fun handleKey(key: Key, ic: InputConnection) {
        // Auto-commit math on space before delegating to the controller (03 §4.1, T2.4).
        if (key.code == KeyCode.Space && mathSource.hasResult) {
            commitMathFromSource()
            stripCoordinator?.updateMath(null)
        }

        try {
            controller.handleKey(key, ic)
        } catch (e: Exception) {
            // This catch is for stack-trace enrichment only — it does NOT provide recovery.
            // The rethrow means the exception still propagates to the main-thread looper,
            // where it will crash the process. This is intentional: logging here ensures the
            // crash report contains the key code and this site rather than just a bare
            // "lambda" frame, which would be unactionable in production.
            //
            // Design note: InputConnection IPC failures (RemoteException, binder-died) and
            // NullPointerExceptions in ComposingTextManager are in the same catch. They are
            // not handled differently here because the goal is crash attribution, not recovery.
            // If a genuine recovery point is added in the future, split into typed catches:
            //   catch (e: android.os.RemoteException) → session clean-up, no rethrow
            //   catch (e: RuntimeException) → rethrow (programming error)
            Log.e(TAG, "handleKey threw unexpectedly for key=${key.code}; session may be unstable [stack-trace enrichment only — process will crash]", e)
            throw e
        }
        syncKeyPlaneRows()
        when (key.code) {
            is KeyCode.Char,
            KeyCode.Backspace,
            KeyCode.Space,
            KeyCode.Enter -> requestStripUpdate()
            else -> Unit
        }
    }

    // ── Strip update path ─────────────────────────────────────────────────────

    /**
     * Queues a strip update from the current mirror state and [fieldPolicy].
     *
     * Math evaluation runs via [MathEvaluator] (debounced); word candidates run via
     * [StripCoordinator.requestUpdate] (coalesced). Both run off the UI thread.
     *
     * Safe to call on the main thread; does not block.
     */
    private fun requestStripUpdate() {
        if (!prefs.enabled) {
            stripCoordinator?.clear()
            mathSource.clear()
            return
        }

        // Math evaluation.
        // Always read from the mirror — never fall back to a synchronous IPC call on the
        // input thread. The mirror is seeded at field entry in onStartInputView via
        // controller.configure(info, restarting, currentInputConnection) → mirror.seed(ic).
        // A blocking getTextBeforeCursor() call here violates T1.5 and can cause ANRs.
        if (fieldPolicy.mathEnabled) {
            val text = controller.mirror.textBefore.toString().takeIf { it.isNotEmpty() }
            if (text != null) {
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
            } else {
                // No text before cursor — clear the math slot.
                evaluator?.cancel()
                stripCoordinator?.updateMath(null)
            }
        } else {
            evaluator?.cancel()
            stripCoordinator?.updateMath(null)
        }

        // Word candidates.
        if (fieldPolicy.suggestionsEnabled) {
            val ctx = buildEditingContext()
            stripCoordinator?.requestUpdate(ctx, fieldPolicy)
        } else {
            // Clear word candidates without clearing the math slot — math is independent.
            stripCoordinator?.requestUpdate(EditingContext.EMPTY, fieldPolicy)
        }
    }

    /**
     * Builds an [EditingContext] from the local mirror without any IPC.
     *
     * The composing word is derived from [KeyboardController.composingText]; textBefore
     * and wordBefore come from the mirror.
     */
    private fun buildEditingContext(): EditingContext {
        val textBefore = controller.mirror.textBefore.toString()
        val composing  = controller.composingText()

        // Extract the last committed word before the cursor for bigram context.
        val wordBefore = if (textBefore.isNotBlank()) {
            // Walk backwards past whitespace to find the previous word boundary.
            val trimmed = textBefore.trimEnd()
            val spaceIdx = trimmed.lastIndexOf(' ')
            if (spaceIdx >= 0) trimmed.substring(spaceIdx + 1).takeIf { it.isNotEmpty() }
            else trimmed.takeIf { it.isNotEmpty() }
        } else null

        return EditingContext(
            composingWord      = composing,
            textBeforeCursor   = textBefore,
            wordBeforeCursor   = wordBefore,
        )
    }

    /**
     * Applies a [StripState] to the suggestion strip.
     *
     * Always called on the main thread (posted by [StripCoordinator]).
     */
    private fun bindStripState(state: StripState) {
        val strip = suggestionStrip ?: return
        strip.bind(
            state     = state,
            onMathTap = {
                commitMathFromSource()
                stripCoordinator?.updateMath(null)
            },
            onWordTap = { candidate ->
                commitWordCandidate(candidate)
                // Immediately re-query so the strip refreshes after the commit.
                requestStripUpdate()
            },
        )
    }

    // ── Commit helpers ────────────────────────────────────────────────────────

    /**
     * Commits the math result held by [mathSource] via the [MathSuggestionSource.CommitAction].
     *
     * The action performs a batch-edit span-replace when [TallyPreferences.replaceExpression]
     * is true, otherwise appends the result. Uses [TallyPreferences.insertExactValue] to
     * decide whether the formatted [display] or the full-precision [exactValue] is inserted.
     *
     * No-ops when [mathSource] has no result or the [InputConnection] is unavailable.
     */
    private fun commitMathFromSource() {
        mathSource.commit { s ->
            val ic = currentInputConnection ?: return@commit
            val textToInsert = if (prefs.insertExactValue) {
                s.exactValue.toPlainString()
            } else {
                s.display
            }

            ic.beginBatchEdit()
            controller.mirror.onBatchEditBegin()
            try {
                if (prefs.replaceExpression) {
                    val textBefore = ic.getTextBeforeCursor(MathEvaluator.MAX_TEXT_LENGTH, 0)
                    if (textBefore == null) {
                        // getTextBeforeCursor returned null — the IC is either dead or this is
                        // a non-compliant editor. Do not fall through to commitText: inserting
                        // without first deleting the expression would produce "2+3 5" (both
                        // the expression AND the result visible), which is data corruption.
                        Log.w(TAG, "commitMathFromSource: getTextBeforeCursor null; abandoning commit to avoid duplicate insertion")
                        return@commit
                    } else {
                        val deleteCount = textBefore.length - s.span.first
                        if (deleteCount > 0) {
                            if (!ic.deleteSurroundingText(deleteCount, 0)) {
                                // deleteSurroundingText returned false — the expression was not
                                // removed. Proceeding with commitText would append the result
                                // after the expression (e.g. "2+3= 5" instead of "5"), which is
                                // user-visible data corruption. Abandon the commit entirely.
                                Log.e(TAG, "commitMathFromSource: deleteSurroundingText failed; " +
                                    "abandoning commit to avoid inserting result alongside expression")
                                return@commit
                            }
                        }
                    }
                }
                if (!ic.commitText(textToInsert, 1)) {
                    Log.w(TAG, "commitMathFromSource: commitText returned false; IC may be invalidated")
                }
            } finally {
                ic.endBatchEdit()
                controller.mirror.onBatchEditEnd()
            }
        }
    }

    /**
     * Commits a word candidate tapped in the suggestion strip.
     *
     * The composing region (if active) is replaced by the candidate text; if no composing
     * region is active the candidate is committed after the cursor. The whole operation is
     * a single batch edit so the editor sees it as atomic and its undo history is clean.
     */
    private fun commitWordCandidate(candidate: Suggestion) {
        val ic = currentInputConnection ?: return
        val composing = controller.composingText()

        ic.beginBatchEdit()
        controller.mirror.onBatchEditBegin()
        try {
            if (composing.isNotEmpty()) {
                // Replace the composing region with the candidate word and commit it.
                if (!ic.setComposingText(candidate.text, 1)) {
                    Log.w(TAG, "commitWordCandidate: setComposingText returned false")
                }
                if (!ic.finishComposingText()) {
                    Log.w(TAG, "commitWordCandidate: finishComposingText returned false")
                }
            } else {
                if (!ic.commitText(candidate.text, 1)) {
                    Log.w(TAG, "commitWordCandidate: commitText returned false")
                }
            }
            // Append a trailing space so the next word starts cleanly (matching Gboard convention).
            if (!ic.commitText(" ", 1)) {
                Log.w(TAG, "commitWordCandidate: space commitText returned false")
            }
        } finally {
            ic.endBatchEdit()
            controller.mirror.onBatchEditEnd()
        }

        // Gate the learning write behind FieldPolicy at the PersonalizationStore seam.
        decoderStack?.personalizationStore?.record(candidate.text, fieldPolicy)
        // Reset composing state so the controller is consistent after the tap commit.
        controller.teardown(null)
    }

    // ── KeyboardHost ──────────────────────────────────────────────────────────

    override fun getTextBeforeCursor(maxLength: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(maxLength, 0)

    override fun insertResult(text: String) {
        val ic = currentInputConnection ?: run {
            // Most common failure mode: the field was dismissed between the evaluation callback
            // and the user's tap on the chip (e.g. switching apps while the chip was visible).
            // Log at warn so the silent result-drop is observable during debugging.
            Log.w(TAG, "insertResult: currentInputConnection is null; result dropped")
            return
        }
        if (!ic.commitText(text, 1)) {
            Log.w(TAG, "insertResult: commitText returned false — IC may have been invalidated")
        }
    }

    override fun clearSuggestion() {
        mathSource.clear()
        stripCoordinator?.updateMath(null)
        suggestionStrip?.clearSuggestion()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Pushes the current controller state to [keyPlane].
     *
     * Converts the controller's [KeyboardState] (and the shift sub-state for the locked visual)
     * to the correct [KeyRow] set. When the number row is enabled it is prepended to the
     * alphabetic layers. Numeric and symbol layers are sourced from JSON data assets via
     * [LayoutLoader]. The key plane redraws and the a11y tree is invalidated automatically
     * via [KeyPlaneView.currentRows].
     */
    private fun syncKeyPlaneRows() {
        val plane = keyPlane ?: return
        val baseRows = when (controller.currentState()) {
            KeyboardState.ALPHA_LOWER -> KeyboardLayout.ALPHA_LOWER
            KeyboardState.ALPHA_UPPER -> when (controller.currentShiftState()) {
                dev.tally.keyboard.engine.ShiftState.LOCKED -> KeyboardLayout.ALPHA_LOCKED
                else -> KeyboardLayout.ALPHA_UPPER
            }
            KeyboardState.NUMERIC -> layoutLoader.numericRows()
            KeyboardState.SYMBOLS -> layoutLoader.symbolRows()
        }

        val rows = if (controller.numberRowEnabled && isAlphaState(controller.currentState())) {
            layoutLoader.numberRow() + baseRows
        } else {
            baseRows
        }

        plane.currentRows = rows
    }

    private fun isAlphaState(state: KeyboardState): Boolean =
        state == KeyboardState.ALPHA_LOWER || state == KeyboardState.ALPHA_UPPER

    private companion object {
        const val DEFAULT_ROW_COUNT = 4
        const val TAG = "TallyInputMethodService"
    }
}
