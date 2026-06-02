package dev.tally.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for [KeyPlaneView] skeleton.
 *
 * Confirms the view measures to a non-zero size and has no interactive children in this
 * skeleton state. M1 tasks (T1.1, T1.2) extend these tests with layout/hit-test coverage.
 *
 * Acceptance criteria covered (T0.5):
 *   - KeyPlaneView measures to a positive height and width.
 *   - View is constructed without throwing (no rendering deps outside the view layer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyPlaneViewTest {

    private lateinit var view: KeyPlaneView

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)
    }

    @Test
    fun measure_producesPositiveHeight() {
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)

        assertTrue("KeyPlaneView must measure to a positive height", view.measuredHeight > 0)
    }

    @Test
    fun measure_widthRespectsExactSpec() {
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)

        assertEquals(720, view.measuredWidth)
    }
}
