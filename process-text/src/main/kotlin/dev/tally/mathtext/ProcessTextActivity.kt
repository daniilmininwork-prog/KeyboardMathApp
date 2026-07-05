package dev.tally.mathtext

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * One-shot PROCESS_TEXT calculator.
 *
 * Launched by the Android text-selection toolbar when the user taps "Tally: Calculate" on a
 * selected fragment in any app. It does exactly one of three things:
 *
 *  1. Editable + valid expression  → replace the selection in place with the result
 *     (setResult(RESULT_OK, EXTRA_PROCESS_TEXT = result); the host app performs the replacement)
 *     and finish immediately — the user never sees this activity.
 *  2. Read-only source (or a host like Chrome, which always invokes process-text read-only)
 *     + valid expression → show the result on a small dismissible card with a Copy action.
 *  3. Not a valid expression (or empty) → show a brief message on the same card and change
 *     nothing (no result is set, so the host leaves the text untouched).
 *
 * The card exists because a Toast cannot survive this activity: the activity is transient, and
 * once it finishes the freezer kills its process before the toast renders ("Toast already
 * killed" — observed on API 35). A window owned by a live, finish-on-tap activity is the
 * reliable way to show the result; it also gives the result a face: big numerals, tap anywhere
 * to dismiss, one Copy button.
 *
 * All evaluation goes through the pure, total [ExpressionEvaluator] (which wraps
 * [dev.tally.math.MathEngine]); intent extras are read defensively; the activity never crashes
 * on any input.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guard against a stray explicit launch (another app firing our component directly).
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) {
            finish()
            return
        }

        val selected: CharSequence? = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val source = selected?.toString()
        if (source.isNullOrBlank()) {
            showCard(expression = null, result = null, message = getString(R.string.error_no_text))
            return
        }

        val result = ExpressionEvaluator.evaluateToDisplayString(
            input = source,
            locale = Locale.getDefault(),
        )

        when {
            result == null ->
                // Not math: say so, change nothing. No result is set, so the host keeps the
                // original selection exactly as it was.
                showCard(expression = null, result = null, message = getString(R.string.error_not_expression))

            readOnly ->
                // Cannot write back into a non-editable source — show the result instead.
                showCard(expression = source, result = result, message = null)

            else -> {
                // Editable source: hand the replacement text back to the host and vanish.
                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
                finish()
            }
        }
    }

    /**
     * A centred card over a dimmed scrim: [expression] (when showing a result) in small type,
     * the [result] in large type with a Copy action, or a single-line [message]. Tapping
     * anywhere outside the card dismisses. Built in code — the module keeps zero layout/theme
     * resources so the whole app stays a single activity and a handful of strings.
     */
    private fun showCard(expression: String?, result: String?, message: String?) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) 0xFF2A2A2E.toInt() else Color.WHITE
        val primary = if (dark) 0xFFF3F3F5.toInt() else 0xDE000000.toInt()
        val secondary = if (dark) 0xB3E8E8EC.toInt() else 0x99000000.toInt()
        val accent = if (dark) 0xFF9FC1FF.toInt() else 0xFF1A56C4.toInt()
        val d = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(surface)
            }
            elevation = 24 * d
            val pad = (28 * d).toInt()
            setPadding(pad, pad, pad, (16 * d).toInt())
            // Consume taps so a tap on the card itself does not fall through to the scrim.
            isClickable = true
        }

        if (message != null) {
            card.addView(TextView(this).apply {
                text = message
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
        }

        if (expression != null) {
            card.addView(TextView(this).apply {
                text = expression
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.START
            })
        }

        if (result != null) {
            card.addView(TextView(this).apply {
                text = getString(R.string.result_readonly, result)
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextIsSelectable(true)
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = getString(R.string.copy_result)
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isAllCaps = true
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                val v = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, v, true)
                setBackgroundResource(v.resourceId)
                val px = (12 * d).toInt()
                setPadding(px, px, px, px)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END; topMargin = (8 * d).toInt() }
                setOnClickListener {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), result))
                    // Android 13+ shows its own "copied" confirmation overlay; only speak up on
                    // older versions, where a toast from the still-alive activity renders fine.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(applicationContext, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            })
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x52000000)
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                val m = (32 * d).toInt()
                setMargins(m, m, m, m)
            })
        }
        setContentView(scrim)
    }
}
