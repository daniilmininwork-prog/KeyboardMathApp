package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.FieldPolicy

/**
 * Derives a [FieldPolicy] from an [EditorInfo].
 *
 * Called exactly once per field in [TallyInputMethodService.onStartInputView]. The resulting
 * policy object is then passed to every surface that must respect field privacy — the key
 * plane (preview suppression), the suggestion strip, the decoder, and the persistence layer.
 *
 * Derivation follows `03-ime-architecture-v2.md §9` and `06-security-privacy-hardening.md §3.1`:
 *
 *   Password variations → all optional features disabled (most-private).
 *   TYPE_TEXT_FLAG_NO_SUGGESTIONS → suggestions, glide, preview, and math all off.
 *   IME_FLAG_NO_PERSONALIZED_LEARNING → learning and persist off (suggestions may still show).
 *   Default plain text → fully permissive.
 *
 * **Fail-closed rule:** when the input type is unrecognised or zero (e.g. in the brief window
 * before the first real field), the factory returns [FieldPolicy.DEFAULT_PRIVATE] rather than
 * [FieldPolicy.PERMISSIVE]. Callers that want the permissive policy for plain text must pass
 * a concrete [EditorInfo] with a valid input type.
 */
internal object FieldPolicyFactory {

    // EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING was added in API 26.
    // The constant value is 0x1000000; we reference it by name so ProGuard/R8 keeps the
    // symbol, and the app still compiles against older SDKs in the minSdk range.
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

    /**
     * Derives the [FieldPolicy] for the given [EditorInfo].
     *
     * All classification logic is self-contained so the policy can be derived and tested
     * without a running IME process.
     */
    fun from(info: EditorInfo): FieldPolicy {
        // Fail-closed: inputType=0 (the EditorInfo default, set before any field is active)
        // must return DEFAULT_PRIVATE rather than falling through to the permissive branch.
        // This ensures the brief window between IME attach and the first real onStartInputView
        // is maximally restrictive. Any caller that wants the permissive policy must supply a
        // concrete EditorInfo with a valid, non-zero inputType.
        if (info.inputType == 0) return FieldPolicy.DEFAULT_PRIVATE

        val cls  = info.inputType and InputType.TYPE_MASK_CLASS
        val varn = info.inputType and InputType.TYPE_MASK_VARIATION

        val isPassword = isPasswordVariation(cls, varn)
        val noSuggestions = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val noLearning = (info.imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        // Any password variation implies the most restrictive policy. Nothing optional is shown.
        if (isPassword) {
            return FieldPolicy(
                learningEnabled    = false,
                suggestionsEnabled = false,
                glideEnabled       = false,
                previewMasked      = true,
                persistAllowed     = false,
                mathEnabled        = false,
            )
        }

        // No-suggestions fields: strip and glide off, preview masked, math off.
        // Learning may still be off if the incognito flag is also set.
        if (noSuggestions) {
            return FieldPolicy(
                learningEnabled    = false,
                suggestionsEnabled = false,
                glideEnabled       = false,
                previewMasked      = true,
                persistAllowed     = false,
                mathEnabled        = false,
            )
        }

        // Incognito / no-learning: suggestions may still show but nothing is persisted
        // and the language model must not be updated with content from this field.
        if (noLearning) {
            return FieldPolicy(
                learningEnabled    = false,
                suggestionsEnabled = true,
                glideEnabled       = true,
                previewMasked      = false,
                persistAllowed     = false,
                mathEnabled        = true,
            )
        }

        // Plain field — fully permissive.
        return FieldPolicy.PERMISSIVE
    }

    /**
     * Returns true if [cls] and [varn] (masked from [EditorInfo.inputType]) denote any
     * password variation.
     *
     * Covers:
     *   - TYPE_CLASS_TEXT + TYPE_TEXT_VARIATION_PASSWORD
     *   - TYPE_CLASS_TEXT + TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
     *   - TYPE_CLASS_TEXT + TYPE_TEXT_VARIATION_WEB_PASSWORD
     *   - TYPE_CLASS_NUMBER + TYPE_NUMBER_VARIATION_PASSWORD
     */
    private fun isPasswordVariation(cls: Int, varn: Int): Boolean =
        (cls == InputType.TYPE_CLASS_TEXT &&
            (varn == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
             varn == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
             varn == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
        (cls == InputType.TYPE_CLASS_NUMBER &&
            varn == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}
