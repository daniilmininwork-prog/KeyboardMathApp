package dev.tally

import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [ImeStateChecker] and [determineOnboardingStep].
 *
 * [ImeStateChecker.isTallySelected] is covered thoroughly because it reads from
 * [Settings.Secure], which Robolectric allows writing to in tests.
 *
 * [ImeStateChecker.isTallyEnabled] is covered for the default false case; the true
 * case requires adding a real [android.view.inputmethod.InputMethodInfo] which is not
 * straightforward in unit tests — it is exercised by the end-to-end flow at runtime.
 *
 * [determineOnboardingStep] is a pure function tested exhaustively.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImeStateCheckerTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    // ── isTallyEnabled ────────────────────────────────────────────────────────

    @Test
    fun isTallyEnabled_defaultState_returnsFalse() {
        // Robolectric returns an empty enabled-IME list by default.
        assertFalse(ImeStateChecker.isTallyEnabled(ctx))
    }

    // ── isTallySelected ───────────────────────────────────────────────────────

    @Test
    fun isTallySelected_noSettingWritten_returnsFalse() {
        assertFalse(ImeStateChecker.isTallySelected(ctx))
    }

    @Test
    fun isTallySelected_otherImeSelected_returnsFalse() {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.example.other/.OtherInputMethod",
        )
        assertFalse(ImeStateChecker.isTallySelected(ctx))
    }

    @Test
    fun isTallySelected_tallySelected_returnsTrue() {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "${ctx.packageName}/.SomeInputMethodService",
        )
        assertTrue(ImeStateChecker.isTallySelected(ctx))
    }

    @Test
    fun isTallySelected_packagePrefixSupersetOfTally_returnsFalse() {
        // "dev.tallyextra/..." must NOT match "dev.tally/..."
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "${ctx.packageName}extra/.SomeInputMethodService",
        )
        assertFalse(ImeStateChecker.isTallySelected(ctx))
    }

    @Test
    fun isTallySelected_emptyString_returnsFalse() {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "",
        )
        assertFalse(ImeStateChecker.isTallySelected(ctx))
    }

    // ── determineOnboardingStep ───────────────────────────────────────────────

    @Test
    fun determineStep_neitherEnabledNorSelected_returnsENABLE() {
        val step = determineOnboardingStep(enabled = false, selected = false)
        assert(step == OnboardingStep.ENABLE) { "Expected ENABLE, got $step" }
    }

    @Test
    fun determineStep_enabledButNotSelected_returnsSELECT() {
        val step = determineOnboardingStep(enabled = true, selected = false)
        assert(step == OnboardingStep.SELECT) { "Expected SELECT, got $step" }
    }

    @Test
    fun determineStep_selected_returnsTRY_IT() {
        val step = determineOnboardingStep(enabled = true, selected = true)
        assert(step == OnboardingStep.TRY_IT) { "Expected TRY_IT, got $step" }
    }

    @Test
    fun determineStep_selectedWithoutEnabledFlag_returnsTRY_IT() {
        // selected=true implies enabled; the logic should still advance to TRY_IT.
        val step = determineOnboardingStep(enabled = false, selected = true)
        assert(step == OnboardingStep.TRY_IT) { "Expected TRY_IT, got $step" }
    }
}
