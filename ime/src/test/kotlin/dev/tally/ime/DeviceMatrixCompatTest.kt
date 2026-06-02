package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.KeyboardHeightPolicy
import dev.tally.keyboard.engine.MathBidiContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device matrix compatibility regression suite.
 *
 * Verifies the P0 contracts that must hold across the Samsung-broad matrix defined in
 * 05-device-framework-compatibility.md §7 and 02-overlay-root-cause-and-redesign.md §6.2.
 *
 * These tests exercise the units-under-test that are shared across all OEM variants.
 * They cannot substitute for physical device runs, but they lock in the behavioural
 * contracts that the matrix is verifying:
 *
 *   - Password-field gate holds for every Samsung password variation.
 *   - FieldPolicy derivation is correct for WebView-style fields (Samsung uses these heavily).
 *   - KeyboardHeightPolicy does not grow without bound in any orientation (jank/occlusion).
 *   - MathBidiContract wraps and unwraps correctly for RTL fields (Arabic/Hebrew).
 *   - PlatformCapabilities SDK gates are logically consistent with one another.
 *   - Multi-display: keyPlane reflows without cached geometry.
 *   - FullscreenMode override ensures no extract-mode in landscape (Samsung DeX / foldables).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceMatrixCompatTest {

    // ── Samsung password-field typing (matrix §7.3) ───────────────────────────

    /**
     * Samsung keyboards present TYPE_TEXT_VARIATION_WEB_PASSWORD for browser login fields.
     * The policy must suppress all suggestions and the math chip so typed characters are
     * never echoed in the suggestion strip or previews.
     */
    @Test
    fun `web password field produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        val policy = FieldPolicyFactory.from(info)
        assertFalse("suggestions must be off for web-password", policy.suggestionsEnabled)
        assertFalse("math must be off for web-password", policy.mathEnabled)
        assertTrue("preview must be masked for web-password", policy.previewMasked)
        assertFalse("learning must be off for web-password", policy.learningEnabled)
        assertFalse("persist must be off for web-password", policy.persistAllowed)
    }

    /**
     * Samsung numeric PIN entry fields use TYPE_CLASS_NUMBER + TYPE_NUMBER_VARIATION_PASSWORD.
     * This combination must trigger the fully-restrictive policy so the numeric keyboard shows
     * but the suggestion strip is empty and math is suppressed.
     */
    @Test
    fun `numeric password field on Samsung produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val policy = FieldPolicyFactory.from(info)
        assertFalse("suggestions must be off for numeric-password", policy.suggestionsEnabled)
        assertFalse("math must be off for numeric-password", policy.mathEnabled)
        assertTrue("preview must be masked for numeric-password", policy.previewMasked)
    }

    /**
     * Samsung's One UI uses TYPE_TEXT_FLAG_NO_SUGGESTIONS on search boxes and address bars
     * where autocorrect could interfere with search intent. This must suppress the strip.
     */
    @Test
    fun `no-suggestions search field suppresses strip and math`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val policy = FieldPolicyFactory.from(info)
        assertFalse("suggestions must be off", policy.suggestionsEnabled)
        assertFalse("math must be off", policy.mathEnabled)
    }

    /**
     * Visible-password fields (Samsung shows these for "show password" eye-toggle inputs).
     * Policy must be fully restrictive even when the characters are currently visible.
     */
    @Test
    fun `visible password field produces fully restrictive policy`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val policy = FieldPolicyFactory.from(info)
        assertFalse("math must be off for visible-password", policy.mathEnabled)
        assertTrue("preview must be masked for visible-password", policy.previewMasked)
        assertFalse("suggestions must be off for visible-password", policy.suggestionsEnabled)
    }

    // ── FieldPolicy ordering for Samsung's mixed-flag fields ──────────────────

    /**
     * Password takes precedence over NO_PERSONALIZED_LEARNING.
     *
     * Samsung's banking apps sometimes combine both flags. The result must be the most
     * restrictive policy (password wins).
     */
    @Test
    fun `password with no-learning flag produces fully restrictive policy (password wins)`() {
        val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val policy = FieldPolicyFactory.from(info)
        // Password classification must win.
        assertFalse("math must be off (password)", policy.mathEnabled)
        assertFalse("suggestions must be off (password)", policy.suggestionsEnabled)
        assertTrue("preview must be masked (password)", policy.previewMasked)
    }

    // ── Height policy: no jank / occlusion on mid-tier devices ───────────────

    /**
     * On a typical Galaxy mid-range (1080×2400, density 2.75) with a 4-row layout,
     * portrait height must be positive and within the 45% screen fraction cap.
     * Exceeding the cap would occlude the content field.
     */
    @Test
    fun `portrait height on Galaxy mid-range stays within screen fraction cap`() {
        val h = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = 1080,
            screenHeightPx = 2400,
            density        = 2.75f,
            rowCount       = 4,
        )
        val cap = (2400 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()

        assertTrue("Portrait height must be > 0", h > 0)
        assertTrue("Portrait height $h must be ≤ cap $cap", h <= cap)
    }

    /**
     * On a Fold inner screen (landscape; ~2267×1840, density 2.625) with 4 rows, the
     * keyboard must still be under the screen-fraction cap and must be positive.
     *
     * The inner screen of a Galaxy Fold is wider than it is tall in book posture;
     * the landscape branch of the height policy must handle this gracefully.
     */
    @Test
    fun `landscape height on Galaxy Fold inner screen stays within screen fraction cap`() {
        val h = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = 2267,
            screenHeightPx = 1840,
            density        = 2.625f,
            rowCount       = 4,
        )
        val cap = (1840 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()

        assertTrue("Fold landscape height must be > 0", h > 0)
        assertTrue("Fold landscape height $h must be ≤ cap $cap", h <= cap)
    }

    /**
     * Low-end device (Galaxy A-series): 720×1480, density 2.0.
     *
     * A 4-row portrait keyboard must not exceed 45% of 1480px. Exceeding this on a
     * small screen would push the content area so high the user cannot see what they are typing.
     */
    @Test
    fun `portrait height on Galaxy A-series low-end device stays within cap`() {
        val h = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = 720,
            screenHeightPx = 1480,
            density        = 2.0f,
            rowCount       = 4,
        )
        val cap = (1480 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()

        assertTrue("Low-end portrait height must be > 0", h > 0)
        assertTrue("Low-end portrait height $h must be ≤ cap $cap", h <= cap)
    }

    /**
     * Number-row enabled (5 rows) must still stay within the cap on a compact phone.
     * The number row is a user opt-in (T1.10); it must not cause the keyboard to occlude
     * the field on mid-range hardware.
     */
    @Test
    fun `5-row number-row portrait height on compact phone stays within cap`() {
        val h = KeyboardHeightPolicy.heightPx(
            screenWidthPx  = 1080,
            screenHeightPx = 2400,
            density        = 2.75f,
            rowCount       = 5,
        )
        val cap = (2400 * KeyboardHeightPolicy.MAX_FRACTION_OF_SCREEN).toInt()

        assertTrue("5-row height must be > 0", h > 0)
        assertTrue("5-row height $h must be ≤ cap $cap", h <= cap)
    }

    /**
     * The landscape height must always be strictly less than the portrait height for the
     * same screen and row count. This is the core property that makes landscape usable —
     * shorter rows mean the content field is not nearly fully occluded.
     */
    @Test
    fun `landscape height is always less than portrait height for same row count`() {
        val portrait  = KeyboardHeightPolicy.heightPx(1080, 2400, 2.75f, rowCount = 4)
        val landscape = KeyboardHeightPolicy.heightPx(2400, 1080, 2.75f, rowCount = 4)

        assertTrue(
            "Landscape height ($landscape) must be < portrait height ($portrait)",
            landscape < portrait,
        )
    }

    // ── RTL / locale: math bidi safety (05 §5.1) ─────────────────────────────

    /**
     * A math result like "15" placed inside an Arabic paragraph must not render as "51".
     * [MathBidiContract.wrapLtr] wraps the string in Unicode LRI/PDI marks so the
     * Unicode Bidi Algorithm treats it as an LTR isolate regardless of paragraph direction.
     */
    @Test
    fun `math result string is wrapped with LTR isolate marks for RTL fields`() {
        val raw     = "1,234.56"
        val wrapped = MathBidiContract.wrapLtr(raw)

        assertTrue(
            "Wrapped string must start with LRI (U+2066)",
            wrapped.startsWith(MathBidiContract.LRI.toString()),
        )
        assertTrue(
            "Wrapped string must end with PDI (U+2069)",
            wrapped.endsWith(MathBidiContract.PDI.toString()),
        )
        assertTrue(
            "MathBidiContract.isWrapped must return true for a wrapped string",
            MathBidiContract.isWrapped(wrapped),
        )
    }

    /**
     * Wrapping is idempotent: calling [MathBidiContract.wrapLtr] on an already-wrapped
     * string must not double-wrap it. Double-wrapping would insert stray bidi mark
     * characters into the committed text.
     */
    @Test
    fun `wrapLtr is idempotent — double-wrap does not add extra marks`() {
        val raw      = "42"
        val once     = MathBidiContract.wrapLtr(raw)
        val twice    = MathBidiContract.wrapLtr(once)

        assertEquals("Double-wrap must equal single-wrap", once, twice)
    }

    /**
     * Unwrapping recovers the original math string.
     *
     * When the IME commits text to an [InputConnection] via [commitText], the LRI/PDI
     * marks must be stripped so the editor stores only the numeric value. Most editors
     * pass through bidi marks invisibly, but keeping them in stored/committed text causes
     * parse failures when the text is later read back as a number.
     */
    @Test
    fun `unwrap recovers the original math string`() {
        val original = "3.14159"
        val wrapped  = MathBidiContract.wrapLtr(original)
        val unwrapped = MathBidiContract.unwrap(wrapped)

        assertEquals("Unwrap must recover the original string", original, unwrapped)
    }

    @Test
    fun `unwrap is a no-op on an already-unwrapped string`() {
        val raw = "100"
        assertEquals("Unwrap of non-wrapped string must be unchanged", raw, MathBidiContract.unwrap(raw))
    }

    @Test
    fun `isWrapped returns false for plain string`() {
        assertFalse("Plain string must not be considered wrapped", MathBidiContract.isWrapped("99"))
    }

    @Test
    fun `isWrapped returns false for empty string`() {
        assertFalse("Empty string must not be considered wrapped", MathBidiContract.isWrapped(""))
    }

    // ── PlatformCapabilities logical consistency ───────────────────────────────

    /**
     * [PlatformCapabilities.hasFoldingFeature] must always return true because the Jetpack
     * Window library backports FoldingFeature to minSdk 26 with no runtime gate.
     * Returning false here would cause the foldable layout path to be skipped even on a Fold.
     */
    @Test
    fun `PlatformCapabilities foldingFeature is always available via Jetpack backport`() {
        assertTrue(
            "hasFoldingFeature must always be true (Jetpack library backport)",
            PlatformCapabilities.hasFoldingFeature,
        )
    }

    // ── Fullscreen mode: mandatory for Samsung DeX / Foldable landscape ────────

    /**
     * [TallyInputMethodService.onEvaluateFullscreenMode] must be overridden and return
     * false. This is mandatory for Samsung DeX, Chromebook, and foldable inner screens
     * where fullscreen extract-mode would hijack the entire display.
     *
     * Verified via reflection so no live IME session is needed.
     */
    @Test
    fun `onEvaluateFullscreenMode override is present and declared directly on TallyInputMethodService`() {
        val method = TallyInputMethodService::class.java
            .getDeclaredMethod("onEvaluateFullscreenMode")
        assertEquals(
            "onEvaluateFullscreenMode must be declared on TallyInputMethodService, not inherited",
            TallyInputMethodService::class.java,
            method.declaringClass,
        )
    }

    // ── Samsung Honeyboard: composing region defence ───────────────────────────

    /**
     * Samsung Honeyboard keeps a persistent composing region and buffers keystrokes.
     * When the keyboard restarts an input view (onStartInputView with restarting=true),
     * the controller must NOT reset composing state — doing so corrupts the text that
     * Honeyboard believes is in-flight.
     *
     * The controller exposes this behaviour via its configure(restarting=true) path.
     */
    @Test
    fun `controller preserves ALPHA_LOWER state across restarting field open`() {
        val controller = KeyboardController()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        // Initial configure establishes ALPHA_LOWER.
        controller.configure(info, restarting = false)
        assertEquals(KeyboardState.ALPHA_LOWER, controller.currentState())

        // restarting=true must leave the keyboard state untouched.
        controller.configure(info, restarting = true)
        assertEquals(
            "State must be preserved on restarting field open (Samsung Honeyboard defence)",
            KeyboardState.ALPHA_LOWER,
            controller.currentState(),
        )
    }

    /**
     * Samsung banking apps often type into NUMERIC fields with passwords. Verify the
     * controller enters NUMERIC state for a number-class field so the keyboard shows digits.
     */
    @Test
    fun `controller enters NUMERIC state for TYPE_CLASS_NUMBER`() {
        val controller = KeyboardController()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        controller.configure(info, restarting = false)
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    /**
     * Phone number fields on Samsung dialler and contacts apps use TYPE_CLASS_PHONE.
     * The controller should serve a numeric layout for these.
     */
    @Test
    fun `controller enters NUMERIC state for TYPE_CLASS_PHONE`() {
        val controller = KeyboardController()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        controller.configure(info, restarting = false)
        assertEquals(KeyboardState.NUMERIC, controller.currentState())
    }

    // ── Zero-inputType (EditorInfo default): fail-closed ─────────────────────

    /**
     * When EditorInfo carries inputType=0 (the platform default before the field populates
     * it, or on some exotic OEM fields), the policy must be fail-closed (DEFAULT_PRIVATE).
     * This is the safety net for the window between IME attach and the first onStartInputView.
     */
    @Test
    fun `zero inputType produces DEFAULT_PRIVATE policy (fail-closed)`() {
        val info = EditorInfo() // inputType = 0 by default
        val policy = FieldPolicyFactory.from(info)
        assertEquals(
            "Zero inputType must map to DEFAULT_PRIVATE",
            FieldPolicy.DEFAULT_PRIVATE,
            policy,
        )
    }

    // ── Multi-display / configuration-change: no cached geometry ─────────────

    /**
     * After a configuration change the height policy must produce correct geometry from
     * fresh dimensions. Specifically, swapping width/height (simulating a rotation from
     * portrait to landscape on a tablet) must change the computed height.
     *
     * This validates the contract that [KeyboardHeightPolicy] never internally caches
     * pixel sizes across calls — it is a pure function that recomputes on every call.
     */
    @Test
    fun `KeyboardHeightPolicy is stateless — same inputs always yield same output`() {
        val first  = KeyboardHeightPolicy.heightPx(1080, 2400, 2.75f, 4)
        val second = KeyboardHeightPolicy.heightPx(1080, 2400, 2.75f, 4)
        assertEquals("Pure function must return identical result for identical inputs", first, second)
    }

    @Test
    fun `KeyboardHeightPolicy reflects changed dimensions immediately after rotation`() {
        // Portrait
        val portrait  = KeyboardHeightPolicy.heightPx(1080, 2400, 2.75f, 4)
        // Landscape (width and height swapped)
        val landscape = KeyboardHeightPolicy.heightPx(2400, 1080, 2.75f, 4)

        // The function is pure and must respond to the new dimensions; landscape must be smaller.
        assertFalse(
            "Rotated dimensions must produce a different height (no stale cache)",
            portrait == landscape,
        )
    }
}
