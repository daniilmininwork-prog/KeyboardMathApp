package dev.tally.overlay

import android.text.InputType

/**
 * Secure-field gate for the overlay (TO.5, `06 §3.2`, audit defect T8).
 *
 * When this returns `true` the overlay must **not** read `node.text`, **not** show a chip, and
 * **never** touch the clipboard — the field is treated as sensitive (AC-2). The decision is made
 * from the only signals an `AccessibilityService` can observe on a node: [AccessibilityNodeInfo
 * .isPassword][android.view.accessibility.AccessibilityNodeInfo.isPassword] and the field's
 * `inputType`. `IME_FLAG_NO_PERSONALIZED_LEARNING` lives in `EditorInfo.imeOptions`, which is not
 * exposed to an a11y service, so it cannot be honoured here — the IME path (`03`) covers it.
 *
 * The detection mirrors `isSensitiveField` in `06 §3.1`: any password variation across the text
 * and number classes, plus the no-suggestions flag. Erring toward suppression is the intended
 * trade-off — the privacy clarity of "no chip in anything sensitive" outweighs the rare loss of a
 * calculation inside such a field.
 */
internal object OverlaySecureField {

    fun isSecure(inputType: Int, isPassword: Boolean): Boolean {
        if (isPassword) return true

        val cls = inputType and InputType.TYPE_MASK_CLASS
        val varn = inputType and InputType.TYPE_MASK_VARIATION
        val isPasswordVariation =
            (cls == InputType.TYPE_CLASS_TEXT &&
                (varn == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    varn == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    varn == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER &&
                    varn == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

        return isPasswordVariation || noSuggestions
    }
}
