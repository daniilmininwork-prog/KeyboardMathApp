package dev.tally

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText

/**
 * DEBUG-ONLY scratch field used by the emulator test harness to read back text the IME
 * commits. It lives in the `debug` source set so it never ships in a release build.
 *
 * Why a plain [Activity] built in code (no AppCompat, no layout XML): the harness only
 * needs a single focusable EditText that the input method can attach to. Avoiding the
 * theme/inflation machinery keeps this dependency-free and immune to resource changes in
 * the main app, so the harness stays stable as the real UI evolves.
 *
 * The field carries contentDescription "tallyfield" so `uiautomator dump` can locate the
 * node unambiguously and the test can scrape its `text=` attribute.
 *
 * Launch with `--ez secure true` to make the field a text-password variation instead. This
 * is how the harness exercises the IME's secure-field path (FieldPolicy.previewMasked):
 * key-preview bubbles must redact the glyph so a typed password never leaks above the finger.
 */
class DebugTypingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opt-in secure mode for the harness's redacted-preview check; defaults to plain text.
        val secure = intent?.getBooleanExtra(EXTRA_SECURE, false) == true

        val field = EditText(this).apply {
            inputType = if (secure) {
                // Password variation: the IME derives FieldPolicy.previewMasked from this, so
                // the key-preview bubble shows a neutral dot rather than the pressed character.
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                // Plain multi-line text input: exercises the keyboard's normal text path
                // (not a number/URL/etc. variant that would change key behaviour).
                // CAP_SENTENCES is included so the harness can exercise auto-capitalisation:
                // getCursorCapsMode only reports caps when the field opts in via this flag, so
                // without it the IME (correctly) never raises shift and the auto-cap path is
                // untestable on this field.
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            gravity = Gravity.TOP or Gravity.START
            contentDescription = CONTENT_DESC
            isFocusableInTouchMode = true
            // TextView.canProcessText() refuses a view whose id is NO_ID (it routes the
            // ACTION_PROCESS_TEXT result back by view id), so without an id the selection
            // toolbar silently omits every process-text action — which this harness exists
            // to exercise against Tally: Calculate.
            id = android.view.View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        setContentView(field)

        // Request focus so the IME opens automatically on launch; the harness still taps
        // the field as a belt-and-suspenders measure on devices that gate the soft input
        // behind a touch.
        field.requestFocus()
    }

    private companion object {
        const val CONTENT_DESC = "tallyfield"
        const val EXTRA_SECURE = "secure"
    }
}
