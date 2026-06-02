package dev.tally

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import dev.tally.design.MathResultChip
import dev.tally.glue.MathEvaluator

/**
 * Onboarding wizard: guides a new user through enabling and selecting Tally as their
 * active keyboard, then shows a seeded try-it field that demonstrates the feature.
 *
 * Three sequential steps:
 *   1. ENABLE — deep-links to the system input method settings screen.
 *   2. SELECT — shows the system input method picker.
 *   3. TRY_IT — pre-seeded EditText with an in-app suggestion chip demo.
 *
 * [onResume] re-checks IME state and auto-advances forward if a step is already satisfied.
 * The wizard can be launched at a specific step via [EXTRA_START_STEP].
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private var tryItEvaluator: MathEvaluator? = null
    private var currentSuggestionText: String? = null
    private var currentStep = OnboardingStep.ENABLE

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        flipper = findViewById(R.id.onboarding_flipper)

        // Step 1 — open system IME settings
        findViewById<Button>(R.id.btn_open_ime_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // Step 2 — show the input method picker
        findViewById<Button>(R.id.btn_show_picker).setOnClickListener {
            @Suppress("DEPRECATION")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }

        // Step 3 — in-app chip demo
        val chip = findViewById<MathResultChip>(R.id.try_it_chip)
        val field = findViewById<EditText>(R.id.try_it_field)

        chip.onTap = {
            val suggestion = currentSuggestionText
            if (suggestion != null) {
                field.append(suggestion)
                currentSuggestionText = null
                chip.dismiss()
            }
        }

        tryItEvaluator = MathEvaluator { suggestion ->
            currentSuggestionText = suggestion?.display
            if (suggestion != null) {
                chip.show(suggestion.display)
            } else {
                chip.dismiss()
            }
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                tryItEvaluator?.onTextChanged(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Done — finish the onboarding flow
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }

        // Determine the starting step (restore saved state, or honour intent extra, or detect)
        currentStep = if (savedInstanceState != null) {
            // getString with a default is non-null on API 12+, but the !! is unnecessary and
            // would crash if the stored value is null via a platform edge case. More critically,
            // valueOf throws IllegalArgumentException if the stored step name is stale (e.g.
            // from a backup restore after an app update that renamed or removed a step).
            // Use explicit try/catch so the failure is visible in crash reports.
            val stepName = savedInstanceState.getString(KEY_STEP, OnboardingStep.ENABLE.name)
                ?: OnboardingStep.ENABLE.name
            try {
                OnboardingStep.valueOf(stepName)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Saved onboarding step '$stepName' is unrecognised (backup restore?); " +
                    "falling back to ENABLE", e)
                OnboardingStep.ENABLE
            }
        } else {
            val extraStep = intent.getStringExtra(EXTRA_START_STEP)
            if (extraStep != null) {
                try {
                    OnboardingStep.valueOf(extraStep)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Intent extra start step '$extraStep' is unrecognised; ignoring", e)
                    null
                }
            } else {
                null
            } ?: determineOnboardingStep(
                enabled  = ImeStateChecker.isTallyEnabled(this),
                selected = ImeStateChecker.isTallySelected(this),
            )
        }

        showStep(currentStep)
    }

    override fun onResume() {
        super.onResume()
        // Auto-advance if the user completed a step while away (e.g. enabled the keyboard).
        val detectedStep = determineOnboardingStep(
            enabled  = ImeStateChecker.isTallyEnabled(this),
            selected = ImeStateChecker.isTallySelected(this),
        )
        if (detectedStep.ordinal > currentStep.ordinal) {
            currentStep = detectedStep
            showStep(currentStep)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STEP, currentStep.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        tryItEvaluator?.cancel()
        tryItEvaluator = null
    }

    private fun showStep(step: OnboardingStep) {
        flipper.displayedChild = step.ordinal
        if (step == OnboardingStep.TRY_IT) seedTryItField()
    }

    private fun seedTryItField() {
        val field = findViewById<EditText>(R.id.try_it_field)
        if (field.text.isNullOrEmpty()) {
            field.setText(SEED_EXPRESSION)
            field.setSelection(field.text.length)
        }
        // Kick off initial evaluation so the chip appears as soon as the step is shown.
        tryItEvaluator?.onTextChanged(field.text?.toString() ?: "")
    }

    companion object {
        const val EXTRA_START_STEP = "start_step"
        private const val KEY_STEP = "current_step"
        private const val SEED_EXPRESSION = "2+2="
        private const val TAG = "OnboardingActivity"

        /** Returns an [Intent] that opens this activity at the try-it step directly. */
        fun tryItIntent(context: Context): Intent =
            Intent(context, OnboardingActivity::class.java)
                .putExtra(EXTRA_START_STEP, OnboardingStep.TRY_IT.name)
    }
}
