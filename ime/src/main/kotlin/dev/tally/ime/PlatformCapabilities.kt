package dev.tally.ime

import android.os.Build

/**
 * Centralises every [Build.VERSION.SDK_INT] gate used by the IME so that OEM-specific
 * and API-version-specific branches are documented and testable in one place.
 *
 * The spec (05-device-framework-compatibility.md §1.2) is explicit: scatter no
 * SDK_INT literals through feature code; route all version checks here. This object
 * is the single source of truth.
 *
 * Each property documents the API that becomes available at that threshold and the
 * fallback strategy below the gate.
 */
internal object PlatformCapabilities {

    /**
     * Per-display IME positioning via [android.view.inputmethod.InputMethodManager.updateDisplay].
     *
     * Below API 29: single-display only; no extra code path (the system will not call
     * updateDisplay on older builds).
     */
    val hasMultiDisplayIme: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    /**
     * [android.view.inputmethod.InputConnection.getSurroundingText] for single-IPC
     * editing-context reads that return both sides of the cursor atomically.
     *
     * Below API 31: fall back to [android.view.inputmethod.InputConnection.getTextBeforeCursor]
     * + [android.view.inputmethod.InputConnection.getTextAfterCursor] in two calls.
     *
     * Note: some OEM firmware declares API 31 but does not override getSurroundingText.
     * [InputConnectionMirror.seed] already has the null-return fallback for that case.
     */
    val hasSurroundingText: Boolean
        get() = Build.VERSION.SDK_INT >= 31

    /**
     * Inline autofill surface ([android.view.inputmethod.InputMethodManager] inline
     * suggestions) for apps that offer autofill via the Autofill service.
     *
     * Below API 30: no inline suggestion row; the system autofill dropdown remains
     * the host app's responsibility.
     */
    val hasInlineAutofill: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    /**
     * Cooperative show/hide synchronisation via [android.view.WindowInsetsAnimation].
     *
     * Below API 30: report stable height and skip the animated inset handshake; the
     * keyboard appears/disappears without the slide animation that smoothly scrolls
     * content out of the way.
     */
    val hasInsetAnimation: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    /**
     * Stylus handwriting ink window via
     * [android.inputmethodservice.InputMethodService.onStartStylusHandwriting].
     *
     * Below API 34: feature is absent; the method is never called.
     */
    val hasStylusHandwriting: Boolean
        get() = Build.VERSION.SDK_INT >= 34

    /**
     * Accessibility composing-vs-commit distinction via
     * [android.view.accessibility.AccessibilityEvent.getTextChangeTypes] and the
     * [android.view.accessibility.AccessibilityEvent.TEXT_DATA_TYPE_COMMITTED] flag.
     *
     * Below API 36 (Android 16/17): use debounce + read-back verify as the universal
     * path (see 02-overlay-root-cause-and-redesign.md §4.3).
     */
    val hasA11yTextChangeType: Boolean
        get() = Build.VERSION.SDK_INT >= 36

    /**
     * Foldable posture and hinge detection via [androidx.window.layout.WindowLayoutInfo]
     * and [androidx.window.layout.FoldingFeature].
     *
     * Available on all supported API levels through the Jetpack Window library backport;
     * no SDK_INT gate is needed. The library returns an empty [WindowLayoutInfo] when
     * there is no foldable feature (single-screen phone), so callers can always
     * subscribe without an API guard.
     */
    val hasFoldingFeature: Boolean
        get() = true   // Jetpack Window library backports to minSdk 26
}
