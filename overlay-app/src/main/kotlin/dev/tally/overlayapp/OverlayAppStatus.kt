package dev.tally.overlayapp

import androidx.annotation.StringRes

/**
 * Pure, framework-free mapping from the live accessibility-service state to what the home
 * screen should render.
 *
 * The actual "is the service enabled?" query lives in
 * [dev.tally.overlay.OverlayPermissionState.isAccessibilityServiceEnabled] — this object does
 * NOT reimplement that detection. It only decides which strings and which content descriptions
 * correspond to a given boolean, so the UI-text decisions are unit-testable without a device or
 * Robolectric (mirrors the `determineOnboardingStep` pattern in the :app module).
 */
object OverlayAppStatus {

    /**
     * The resolved presentation for a given enabled state: the title, the explanatory
     * sub-text, the CTA button label, and the status content description for TalkBack.
     */
    data class Presentation(
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        @StringRes val ctaLabelRes: Int,
        @StringRes val statusContentDescRes: Int,
    )

    /**
     * Maps [enabled] (the result of OverlayPermissionState.isAccessibilityServiceEnabled) to
     * the strings the home screen shows.
     *
     * The CTA label intentionally differs by state ("Enable overlay" vs. "Manage accessibility
     * service") but BOTH route to the same consent/settings flow — there is never a dead end.
     */
    fun presentationFor(enabled: Boolean): Presentation =
        if (enabled) {
            Presentation(
                titleRes = R.string.overlay_app_status_on_title,
                subtitleRes = R.string.overlay_app_status_on_sub,
                ctaLabelRes = R.string.overlay_app_btn_manage,
                statusContentDescRes = R.string.overlay_app_status_icon_on_desc,
            )
        } else {
            Presentation(
                titleRes = R.string.overlay_app_status_off_title,
                subtitleRes = R.string.overlay_app_status_off_sub,
                ctaLabelRes = R.string.overlay_app_btn_enable,
                statusContentDescRes = R.string.overlay_app_status_icon_off_desc,
            )
        }
}
