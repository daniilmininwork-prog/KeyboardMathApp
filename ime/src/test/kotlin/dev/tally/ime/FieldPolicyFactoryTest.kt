package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.FieldPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exhaustive derivation tests for [FieldPolicyFactory].
 *
 * Verifies every branch in the factory against the rules from
 * `03-ime-architecture-v2.md §9` and `06-security-privacy-hardening.md §3.1`.
 *
 * Acceptance criteria (T1.13):
 *   - Password / NO_SUGGESTIONS → all features off (most-private).
 *   - IME_FLAG_NO_PERSONALIZED_LEARNING → learning+persist off; suggestions/math still on.
 *   - Plain text → PERMISSIVE policy.
 *   - previewMasked true iff password or no-suggestions.
 *   - glideEnabled false whenever suggestionsEnabled is false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FieldPolicyFactoryTest {

    // convenience builder so tests are terse
    private fun info(inputType: Int, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
    }

    // ── password variations ───────────────────────────────────────────────────

    @Test
    fun `text password → learning off`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(p.learningEnabled)
    }

    @Test
    fun `text password → suggestions off`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(p.suggestionsEnabled)
    }

    @Test
    fun `text password → glide off`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(p.glideEnabled)
    }

    @Test
    fun `text password → preview masked`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(p.previewMasked)
    }

    @Test
    fun `text password → persist disallowed`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(p.persistAllowed)
    }

    @Test
    fun `text password → math disabled`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(p.mathEnabled)
    }

    @Test
    fun `visible password → same as text password policy`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.suggestionsEnabled)
    }

    @Test
    fun `web password → same as text password policy`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.persistAllowed)
    }

    @Test
    fun `numeric PIN password → same as text password policy`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.learningEnabled)
    }

    // ── TYPE_TEXT_FLAG_NO_SUGGESTIONS ─────────────────────────────────────────

    @Test
    fun `no-suggestions flag → suggestions off`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.suggestionsEnabled)
    }

    @Test
    fun `no-suggestions flag → glide off (glide requires suggestions)`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.glideEnabled)
    }

    @Test
    fun `no-suggestions flag → preview masked`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertTrue(p.previewMasked)
    }

    @Test
    fun `no-suggestions flag → math disabled`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.mathEnabled)
    }

    @Test
    fun `no-suggestions flag → persist disallowed`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.persistAllowed)
    }

    // ── IME_FLAG_NO_PERSONALIZED_LEARNING ────────────────────────────────────

    @Test
    fun `no-learning flag → learning off`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertFalse(p.learningEnabled)
    }

    @Test
    fun `no-learning flag → persist disallowed`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertFalse(p.persistAllowed)
    }

    @Test
    fun `no-learning flag → suggestions still enabled`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertTrue(p.suggestionsEnabled)
    }

    @Test
    fun `no-learning flag → glide still enabled`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertTrue(p.glideEnabled)
    }

    @Test
    fun `no-learning flag → preview not masked`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertFalse(p.previewMasked)
    }

    @Test
    fun `no-learning flag → math still enabled`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT, imeOptions = 0x1000000))
        assertTrue(p.mathEnabled)
    }

    // ── plain fields ──────────────────────────────────────────────────────────

    @Test
    fun `plain TYPE_CLASS_TEXT → PERMISSIVE`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT))
        assertEquals(FieldPolicy.PERMISSIVE, p)
    }

    @Test
    fun `email address → PERMISSIVE (not a password)`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals(FieldPolicy.PERMISSIVE, p)
    }

    @Test
    fun `TYPE_CLASS_NUMBER plain → PERMISSIVE`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_NUMBER))
        assertEquals(FieldPolicy.PERMISSIVE, p)
    }

    @Test
    fun `TYPE_CLASS_PHONE → PERMISSIVE`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_PHONE))
        assertEquals(FieldPolicy.PERMISSIVE, p)
    }

    // ── invariant: glideEnabled ≤ suggestionsEnabled ──────────────────────────

    @Test
    fun `glide is never true when suggestions are false - password`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        // If suggestions are off, glide must also be off.
        assertFalse("glide must be off when suggestions off", p.suggestionsEnabled && !p.glideEnabled)
        assertFalse(p.glideEnabled)
    }

    @Test
    fun `glide is never true when suggestions are false - no-suggestions`() {
        val p = FieldPolicyFactory.from(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(p.glideEnabled)
    }

    // ── interaction: TYPE_TEXT_FLAG_NO_SUGGESTIONS AND IME_FLAG_NO_PERSONALIZED_LEARNING ────

    /**
     * Verifies branch-priority ordering when both TYPE_TEXT_FLAG_NO_SUGGESTIONS and
     * IME_FLAG_NO_PERSONALIZED_LEARNING are set simultaneously.
     *
     * The factory checks isPassword → noSuggestions → noLearning in priority order; the
     * noSuggestions branch wins and must produce the full restrictive policy. If the branch
     * order were reversed (noLearning checked first), the result would be a policy where
     * previewMasked=false and mathEnabled=true — silently leaking for fields that are both
     * no-suggestions and incognito (e.g. Firefox's private-mode address bar).
     *
     * Issue #23 in the M3 review.
     */
    @Test
    fun `no-suggestions AND no-learning simultaneously — no-suggestions branch wins (most restrictive)`() {
        val p = FieldPolicyFactory.from(
            info(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                imeOptions = 0x1000000, // IME_FLAG_NO_PERSONALIZED_LEARNING
            )
        )
        // The noSuggestions branch must win and produce the full restrictive policy.
        assertFalse("suggestionsEnabled must be false", p.suggestionsEnabled)
        assertFalse("glideEnabled must be false",       p.glideEnabled)
        assertTrue ("previewMasked must be true",        p.previewMasked)
        assertFalse("mathEnabled must be false",         p.mathEnabled)
        assertFalse("persistAllowed must be false",      p.persistAllowed)
        assertFalse("learningEnabled must be false",     p.learningEnabled)
    }

    // ── fail-closed: inputType=0 ──────────────────────────────────────────────

    /**
     * Verifies the fail-closed rule documented in [FieldPolicyFactory]:
     * when [EditorInfo.inputType] is 0 (unset — the default for a plain [EditorInfo]),
     * the factory must return [FieldPolicy.DEFAULT_PRIVATE] rather than [FieldPolicy.PERMISSIVE].
     *
     * This closes the brief window between IME attach and the first real [onStartInputView].
     * If this guard were accidentally removed, a plain [EditorInfo()] would fall through to
     * the permissive branch and the keyboard would be wide-open on startup.
     */
    @Test
    fun `EditorInfo with inputType=0 returns DEFAULT_PRIVATE (fail-closed)`() {
        val p = FieldPolicyFactory.from(EditorInfo())  // inputType defaults to 0

        assertFalse("fail-closed: learningEnabled must be false for inputType=0",    p.learningEnabled)
        assertFalse("fail-closed: suggestionsEnabled must be false for inputType=0", p.suggestionsEnabled)
        assertFalse("fail-closed: mathEnabled must be false for inputType=0",        p.mathEnabled)
        assertFalse("fail-closed: persistAllowed must be false for inputType=0",     p.persistAllowed)
    }
}
