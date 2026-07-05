package dev.tally.mathtext

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.util.Locale

/**
 * One-shot PROCESS_TEXT calculator.
 *
 * Launched by the Android text-selection toolbar when the user taps "Tally: Calculate" on a
 * selected fragment in any app. The activity has no persistent UI (transparent theme) and does
 * exactly one of three things, then finishes:
 *
 *  1. Editable + valid expression  → replace the selection in place with the result
 *     (setResult(RESULT_OK, EXTRA_PROCESS_TEXT = result); the host app performs the replacement).
 *  2. Read-only / non-editable + valid expression → show the result in a Toast so the user can
 *     read it (the framework will not let us write back into a non-editable source).
 *  3. Not a valid expression (or empty) → show a brief, friendly message and finish WITHOUT
 *     altering the selection (no result is set, so the host leaves the text untouched).
 *
 * The activity never crashes on any input: all evaluation goes through the pure, total
 * [ExpressionEvaluator] (which wraps [dev.tally.math.MathEngine]), and the intent extras are read
 * defensively.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleProcessText()
        // Always finish: this activity is transient. finish() is safe to call from onCreate.
        finish()
    }

    private fun handleProcessText() {
        // The framework only ever launches us with ACTION_PROCESS_TEXT, but guard anyway so a
        // stray launch (e.g. from another app firing the explicit component) is handled, not crashed.
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) {
            return
        }

        // EXTRA_PROCESS_TEXT is a CharSequence; may be absent or empty. getCharSequenceExtra is
        // null-safe and never throws on a missing/odd extra.
        val selected: CharSequence? = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val source = selected?.toString()
        if (source.isNullOrBlank()) {
            toast(getString(R.string.error_no_text))
            return
        }

        // Pure evaluation — returns null for anything that is not a confident expression.
        val result = ExpressionEvaluator.evaluateToDisplayString(
            input = source,
            locale = Locale.getDefault(),
        )

        if (result == null) {
            // Not a math expression: tell the user, change nothing. No result is set, so the host
            // keeps the original selection exactly as it was.
            toast(getString(R.string.error_not_expression))
            return
        }

        if (readOnly) {
            // Source is non-editable: we cannot write back. Show the result so the user can read it.
            toast(getString(R.string.result_readonly, result))
            return
        }

        // Editable source + valid result: hand the replacement text back to the host. Setting
        // EXTRA_PROCESS_TEXT in the RESULT_OK intent is what tells the host to replace the
        // selection in place.
        val data = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result)
        setResult(RESULT_OK, data)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
