package dev.tally.keyboard.engine

/**
 * Privacy contract for a single editor field, derived once per [onStartInputView] and
 * passed to every surface that must respect field sensitivity.
 *
 * This is a pure-JVM value object — no Android imports allowed. The companion factory
 * that reads [android.view.inputmethod.EditorInfo] lives in the `ime` module
 * ([dev.tally.ime.FieldPolicyFactory]).
 *
 * Default is most-private: when the field type is ambiguous or the derivation call is
 * skipped, callers get the conservative variant that suppresses all optional features.
 *
 * The properties map to the spec in `03-ime-architecture-v2.md §9` and the detailed
 * derivation rules in `06-security-privacy-hardening.md §3.1`.
 */
data class FieldPolicy(
    /**
     * Whether the decoder may update its per-user language model for this field.
     *
     * False when [EditorInfo.imeOptions] carries `IME_FLAG_NO_PERSONALIZED_LEARNING`
     * (set by browsers in incognito mode, by password managers, etc.). Also false on
     * any password variation, since learning content from a password field is a
     * data-minimisation violation regardless of the flag's presence.
     */
    val learningEnabled: Boolean,

    /**
     * Whether word prediction and autocorrection candidates may be shown.
     *
     * False for password fields and for fields that set [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS].
     * Suppressing suggestions prevents typed passwords or PINs from appearing in the strip.
     */
    val suggestionsEnabled: Boolean,

    /**
     * Whether glide/swipe typing is permitted.
     *
     * Always false when [suggestionsEnabled] is false — a glide path requires a candidate
     * to surface, and showing one in a password field is the same leak as showing a word
     * suggestion. May be independently suppressed in future (e.g. when glide ships).
     */
    val glideEnabled: Boolean,

    /**
     * When true, key-preview popups must be suppressed.
     *
     * Showing a magnified bubble of the pressed key while a password is being typed would
     * reveal the character to shoulder surfers or screen-recorders. Set for all password
     * variations and for any field where [suggestionsEnabled] is false.
     */
    val previewMasked: Boolean,

    /**
     * Whether the keyboard may write to any persistent store for this field's content.
     *
     * Covers the learning store, clipboard history, recents, and any other persistence layer.
     * False for all password fields and for `NO_PERSONALIZED_LEARNING` fields.
     */
    val persistAllowed: Boolean,

    /**
     * Whether the math result chip may be displayed for this field.
     *
     * Math evaluation itself is on-device and stateless, so no content is transmitted or
     * stored by the computation. The risk is *display*: showing a chip whose label echoes
     * text typed into a masked field. Spec recommendation (`03 §9`, `06 §3.1`): suppress
     * when [previewMasked] is true for clarity, even though the underlying computation is
     * harmless.
     */
    val mathEnabled: Boolean,
) {

    companion object {
        /**
         * Conservative default used before a real field is active.
         *
         * Suppresses all optional features so that no surface accidentally shows sensitive
         * content during the brief window between IME attach and [onStartInputView].
         */
        val DEFAULT_PRIVATE = FieldPolicy(
            learningEnabled   = false,
            suggestionsEnabled = false,
            glideEnabled      = false,
            previewMasked     = true,
            persistAllowed    = false,
            mathEnabled       = false,
        )

        /**
         * Permissive policy for a plain text field with no restrictions.
         *
         * Used in tests and as the base that [FieldPolicyFactory] starts from before
         * applying the per-field flags.
         */
        val PERMISSIVE = FieldPolicy(
            learningEnabled   = true,
            suggestionsEnabled = true,
            glideEnabled      = true,
            previewMasked     = false,
            persistAllowed    = true,
            mathEnabled       = true,
        )
    }
}
