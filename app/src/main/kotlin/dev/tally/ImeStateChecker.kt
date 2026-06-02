package dev.tally

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Queries the system for Tally's current IME status.
 *
 * Two distinct states are tracked:
 *  - **Enabled** — Tally appears in the user's enabled keyboards list.
 *  - **Selected** — Tally is the system's active (default) input method.
 *
 * Both must be true for the keyboard to function in real apps.
 */
object ImeStateChecker {

    fun isTallyEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    fun isTallySelected(context: Context): Boolean {
        val active = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        // DEFAULT_INPUT_METHOD is "package/ComponentName"; match strictly on the package prefix.
        return active?.startsWith("${context.packageName}/") == true
    }
}

/** The three sequential steps a new user must complete before Tally is ready. */
enum class OnboardingStep { ENABLE, SELECT, TRY_IT }

/**
 * Pure function: maps current IME state to the step the user should be shown.
 * Skips earlier steps when they are already satisfied.
 */
fun determineOnboardingStep(enabled: Boolean, selected: Boolean): OnboardingStep = when {
    selected -> OnboardingStep.TRY_IT
    enabled  -> OnboardingStep.SELECT
    else     -> OnboardingStep.ENABLE
}
