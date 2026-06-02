package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that the keyboard's field guard correctly suppresses arithmetic suggestions,
 * key previews, learning, and persistence in sensitive fields.
 *
 * T1.13 moved the password classification logic from TallyInputMethodService into
 * [FieldPolicyFactory]. Tests here drive the factory directly so the assertions are
 * tied to the authoritative classification, not a copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureFieldGuardTest {

    // ── password variations → fully restricted policy ─────────────────────────

    @Test
    fun `TYPE_TEXT_VARIATION_PASSWORD produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse("learning", p.learningEnabled)
        assertFalse("suggestions", p.suggestionsEnabled)
        assertFalse("glide", p.glideEnabled)
        assertTrue ("previewMasked", p.previewMasked)
        assertFalse("persist", p.persistAllowed)
        assertFalse("math", p.mathEnabled)
    }

    @Test
    fun `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.suggestionsEnabled)
        assertFalse(p.persistAllowed)
    }

    @Test
    fun `TYPE_NUMBER_VARIATION_PASSWORD produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.learningEnabled)
    }

    @Test
    fun `TYPE_TEXT_VARIATION_WEB_PASSWORD produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse(p.mathEnabled)
        assertTrue (p.previewMasked)
        assertFalse(p.suggestionsEnabled)
    }

    // ── TYPE_TEXT_FLAG_NO_SUGGESTIONS → restricted policy ────────────────────

    @Test
    fun `TYPE_TEXT_FLAG_NO_SUGGESTIONS disables suggestions, glide, math, persist`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse("suggestions", p.suggestionsEnabled)
        assertFalse("glide", p.glideEnabled)
        assertFalse("math", p.mathEnabled)
        assertTrue ("previewMasked", p.previewMasked)
        assertFalse("persist", p.persistAllowed)
        assertFalse("learning", p.learningEnabled)
    }

    // ── IME_FLAG_NO_PERSONALIZED_LEARNING → incognito policy ─────────────────

    @Test
    fun `IME_FLAG_NO_PERSONALIZED_LEARNING disables learning and persist but not suggestions`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            // 0x1000000 = IME_FLAG_NO_PERSONALIZED_LEARNING
            imeOptions = 0x1000000
        }
        val p = FieldPolicyFactory.from(info)
        assertFalse("learning", p.learningEnabled)
        assertFalse("persist", p.persistAllowed)
        // Suggestions, glide, preview, and math are still allowed for incognito.
        assertTrue ("suggestions", p.suggestionsEnabled)
        assertTrue ("glide", p.glideEnabled)
        assertFalse("previewMasked", p.previewMasked)
        assertTrue ("math", p.mathEnabled)
    }

    // ── plain text → permissive policy ───────────────────────────────────────

    @Test
    fun `TYPE_CLASS_TEXT plain produces permissive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val p = FieldPolicyFactory.from(info)
        assertTrue (p.learningEnabled)
        assertTrue (p.suggestionsEnabled)
        assertTrue (p.glideEnabled)
        assertFalse(p.previewMasked)
        assertTrue (p.persistAllowed)
        assertTrue (p.mathEnabled)
    }

    @Test
    fun `TYPE_TEXT_VARIATION_EMAIL_ADDRESS is not password - permissive`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val p = FieldPolicyFactory.from(info)
        assertTrue (p.suggestionsEnabled)
        assertFalse(p.previewMasked)
        assertTrue (p.mathEnabled)
    }

    @Test
    fun `TYPE_CLASS_NUMBER plain is not a password field`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val p = FieldPolicyFactory.from(info)
        assertTrue (p.suggestionsEnabled)
        assertFalse(p.previewMasked)
    }

    @Test
    fun `TYPE_CLASS_PHONE is not a password field`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val p = FieldPolicyFactory.from(info)
        assertTrue (p.suggestionsEnabled)
        assertFalse(p.previewMasked)
    }

    // ── controller layout mode for password fields ────────────────────────────

    @Test
    fun `controller configures to ALPHA_LOWER for a text password field`() {
        val controller = KeyboardController()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        controller.configure(info, restarting = false)
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())
    }

    @Test
    fun `controller configures to NUMERIC for a number password field`() {
        val controller = KeyboardController()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        controller.configure(info, restarting = false)
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }
}
