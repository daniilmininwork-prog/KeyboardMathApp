package dev.tally.overlayapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayAppStatus], the pure mapping from accessibility-service state to home
 * screen copy. Robolectric is used only to resolve the string resources the presentation points
 * at — the logic under test is framework-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlayAppStatusTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun enabledState_usesOnTitleAndManageCta() {
        val p = OverlayAppStatus.presentationFor(enabled = true)

        assertEquals(R.string.overlay_app_status_on_title, p.titleRes)
        assertEquals(R.string.overlay_app_status_on_sub, p.subtitleRes)
        // Enabled => CTA invites managing, not enabling, the service.
        assertEquals(R.string.overlay_app_btn_manage, p.ctaLabelRes)
        assertEquals(R.string.overlay_app_status_icon_on_desc, p.statusContentDescRes)
    }

    @Test
    fun disabledState_usesOffTitleAndEnableCta() {
        val p = OverlayAppStatus.presentationFor(enabled = false)

        assertEquals(R.string.overlay_app_status_off_title, p.titleRes)
        assertEquals(R.string.overlay_app_status_off_sub, p.subtitleRes)
        assertEquals(R.string.overlay_app_btn_enable, p.ctaLabelRes)
        assertEquals(R.string.overlay_app_status_icon_off_desc, p.statusContentDescRes)
    }

    @Test
    fun titleAndCta_differBetweenStates() {
        val on = OverlayAppStatus.presentationFor(true)
        val off = OverlayAppStatus.presentationFor(false)

        // The two states must be visually distinguishable — no dead-end identical UI.
        assertNotEquals(on.titleRes, off.titleRes)
        assertNotEquals(on.ctaLabelRes, off.ctaLabelRes)
        assertNotEquals(on.statusContentDescRes, off.statusContentDescRes)
    }

    @Test
    fun everyReferencedStringResource_resolvesToNonBlankText() {
        // Guards against a presentation pointing at a deleted/renamed string id.
        for (enabled in listOf(true, false)) {
            val p = OverlayAppStatus.presentationFor(enabled)
            for (resId in listOf(p.titleRes, p.subtitleRes, p.ctaLabelRes, p.statusContentDescRes)) {
                assertEquals(
                    "Resource $resId must resolve to non-blank copy",
                    true,
                    ctx.getString(resId).isNotBlank(),
                )
            }
        }
    }
}
