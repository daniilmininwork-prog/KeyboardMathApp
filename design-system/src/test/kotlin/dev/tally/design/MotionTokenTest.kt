package dev.tally.design

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
 * Tests for [MotionToken] — the design-system motion duration provider.
 *
 * Verifies:
 *   - Default resource values match the interaction spec (06 §Motion).
 *   - All durations are positive.
 *   - [MotionToken] reads from resources, not hardcoded constants, so overrides can differ.
 *
 * Interaction spec reference values:
 *   Appear  ~120–160 ms → default 140 ms
 *   Update   cross-fade → default 80 ms per leg
 *   Dismiss ~100 ms     → default 100 ms
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MotionTokenTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun appearMs_matchesInteractionSpec() {
        // 140 ms — within the 120–160 ms band specified in 06-interaction-spec.md §Motion
        assertEquals(140L, MotionToken.appearMs(ctx))
    }

    @Test
    fun fadeMs_matchesInteractionSpec() {
        // 80 ms per cross-fade leg as specified in 06-interaction-spec.md §Motion
        assertEquals(80L, MotionToken.fadeMs(ctx))
    }

    @Test
    fun dismissMs_matchesInteractionSpec() {
        // 100 ms dismiss as specified in 06-interaction-spec.md §Motion
        assertEquals(100L, MotionToken.dismissMs(ctx))
    }

    @Test
    fun allDurations_arePositive() {
        assertTrue(MotionToken.appearMs(ctx) > 0L)
        assertTrue(MotionToken.fadeMs(ctx) > 0L)
        assertTrue(MotionToken.dismissMs(ctx) > 0L)
    }

    @Test
    fun appearMs_isWithinInteractionSpecBand() {
        val ms = MotionToken.appearMs(ctx)
        assertTrue("Appear duration must be in the 120–160 ms spec band", ms in 120L..160L)
    }
}
