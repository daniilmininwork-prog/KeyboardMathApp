package dev.tally.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayPermissionState].
 *
 * Phase 7 acceptance criteria: declining/revoking permissions leaves the keyboard
 * fully working (i.e. the absence of the service is correctly detected so the app
 * can handle that state gracefully).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlayPermissionStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `returns false when no accessibility services are enabled`() {
        // In a fresh Robolectric context no services are registered, so this must be false.
        assertFalse(OverlayPermissionState.isAccessibilityServiceEnabled(context))
    }

    @Test
    fun `does not throw on any non-null context`() {
        // Smoke test: the method must never throw.
        OverlayPermissionState.isAccessibilityServiceEnabled(context)
    }
}
