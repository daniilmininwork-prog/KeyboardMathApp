package dev.tally.ime

import android.os.Looper
import dev.tally.glue.MathEvaluator
import dev.tally.math.Suggestion as MathSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Regression test for the onUpdateSelection-driven evaluation path (commit 369f734).
 *
 * Background: paste, autofill, and `adb input text` all deliver text through
 * [android.inputmethodservice.InputMethodService.onUpdateSelection] rather than through
 * [handleKey]. Before the fix in commit 369f734 the evaluator was only triggered from
 * [handleKey], so pasted text containing `=` never produced a math chip.
 *
 * This test verifies the mirror.reconcile() → requestStripUpdate() chain that was added
 * by that fix. A regression (e.g. reconcile returning false always, or the evaluation not
 * being requested) would cause pasted arithmetic expressions to silently produce no chip.
 *
 * Issue #21 in the M3 review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnUpdateSelectionEvaluationTest {

    private val syncExec = Executor { it.run() }
    private lateinit var mirror: InputConnectionMirror

    @Before
    fun setUp() {
        mirror = InputConnectionMirror()
    }

    /**
     * Simulates an external text change (paste / autofill / adb) by:
     *   1. Seeding the mirror with new text via a fake IC.
     *   2. Calling reconcile() with updated selection coordinates.
     *   3. Asserting reconcile() returns true (triggering evaluation).
     *   4. Running the evaluator with the mirror's textBefore and asserting a chip appears.
     *
     * This is the exact path triggered by TallyInputMethodService.onUpdateSelection:
     *   reconcile(newSelStart, newSelEnd, ic) → if true → requestStripUpdate()
     *   → evaluator.onTextChanged(mirror.textBefore)
     */
    @Test
    fun paste_withEqualsSign_triggersEvaluation() {
        // Arrange: fake IC that reports "2+3=" as the text before cursor.
        val fakeIc = TextBeforeFakeIc("2+3=")

        // Act — step 1: reconcile simulates onUpdateSelection after a paste event.
        // The mirror was previously empty; now the cursor is at position 4.
        val shouldEvaluate = mirror.reconcile(newSelStart = 4, newSelEnd = 4, ic = fakeIc)

        // Assert step 1: reconcile must return true (external change, not our own batch).
        assertEquals("paste must set shouldEvaluate = true", true, shouldEvaluate)
        assertEquals("mirror textBefore must reflect pasted text", "2+3=", mirror.textBefore.toString())

        // Act — step 2: simulate the requestStripUpdate() → evaluator path.
        var lastMath: MathSuggestion? = null
        val evaluator = MathEvaluator(debounceMs = 0L, bgExecutor = syncExec) { lastMath = it }
        evaluator.onTextChanged(mirror.textBefore.toString())
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()

        // Assert step 2: the evaluator must have produced a math chip.
        assertNotNull("paste of '2+3=' must produce a math chip; regression re-introduced if null", lastMath)
        assertEquals("chip display must be '5'", "5", lastMath!!.display)
    }

    /**
     * Verifies that reconcile() returns false when isMidBatchEdit is true, preventing
     * the double-fire that would occur when our own commitText triggers onUpdateSelection.
     */
    @Test
    fun ownCommit_doesNotTriggerEvaluation() {
        val fakeIc = TextBeforeFakeIc("5+5=")

        // Simulate our own batch edit being in progress.
        mirror.onBatchEditBegin()

        val shouldEvaluate = mirror.reconcile(newSelStart = 4, newSelEnd = 4, ic = fakeIc)

        assertEquals("own batch must NOT trigger evaluation", false, shouldEvaluate)
    }

    /**
     * Verifies that a pasted expression that changes the text (new text != previous text)
     * causes reconcile to return true, even when the cursor position is the same.
     *
     * This covers the autofill case where the field contents are replaced atomically.
     */
    @Test
    fun autofill_textChange_triggersEvaluation() {
        // Seed the mirror with some initial text.
        val initialIc = TextBeforeFakeIc("hello")
        mirror.reconcile(newSelStart = 5, newSelEnd = 5, ic = initialIc)

        // Autofill replaces the content with arithmetic text.
        val autofillIc = TextBeforeFakeIc("100+50=")
        val shouldEvaluate = mirror.reconcile(newSelStart = 7, newSelEnd = 7, ic = autofillIc)

        assertEquals("autofill text change must trigger evaluation", true, shouldEvaluate)
        assertEquals("mirror must reflect autofilled text", "100+50=", mirror.textBefore.toString())
    }

    /**
     * Verifies that reconcile() with a null IC (field dismissed) returns false and
     * does not leave stale text in the mirror.
     */
    @Test
    fun fieldDismissed_nullIc_doesNotTriggerEvaluation() {
        // First seed some text.
        val fakeIc = TextBeforeFakeIc("2+2=")
        mirror.reconcile(newSelStart = 4, newSelEnd = 4, ic = fakeIc)

        // Field is dismissed — IC becomes null.
        val shouldEvaluate = mirror.reconcile(newSelStart = 0, newSelEnd = 0, ic = null)

        assertEquals("null IC must NOT trigger evaluation", false, shouldEvaluate)
        assertEquals("mirror must be cleared after null IC", "", mirror.textBefore.toString())
    }

    // ── Fake IC helper ────────────────────────────────────────────────────────

    /**
     * A minimal fake [InputConnection] that returns a fixed text before cursor.
     *
     * Used to exercise the mirror.reconcile() → mirror.seed(ic) path without a running IME.
     */
    private class TextBeforeFakeIc(
        private val textBefore: String,
    ) : android.view.inputmethod.BaseInputConnection(
        android.widget.TextView(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
        false,
    ) {
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            textBefore.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
    }
}
