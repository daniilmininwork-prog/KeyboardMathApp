package dev.tally.ime

import android.content.Context
import android.content.SharedPreferences
import dev.tally.glue.TallyPreferences
import dev.tally.math.PercentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [TallyPreferences] using Robolectric.
 *
 * Acceptance criteria covered:
 *   - [TallyPreferences.percentMode] corrupt-value recovery: writing an unrecognised string
 *     to the shared-pref key must fall back to [PercentMode.ADDITIVE] rather than crashing.
 *   - [TallyPreferences.insertExactValue] default is false; round-trip read/write works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TallyPreferencesTest {

    private lateinit var context: Context
    private lateinit var prefs: TallyPreferences
    private lateinit var sharedPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = TallyPreferences(context)
        sharedPrefs = context.getSharedPreferences(TallyPreferences.FILE_NAME, Context.MODE_PRIVATE)
    }

    // ── percentMode corrupt-value recovery ────────────────────────────────────

    /**
     * Validates the [TallyPreferences.percentMode] getter's IllegalArgumentException recovery.
     *
     * A backup restore from a newer app version (or a manual SharedPreferences edit) could
     * write an unknown enum name. The getter must fall back to ADDITIVE rather than crashing.
     *
     * This test exercises the try/catch in [TallyPreferences.percentMode] getter directly by
     * injecting an unrecognised string via [SharedPreferences.edit] — the same path a backup
     * restore would take.
     */
    @Test
    fun percentMode_unknownStoredValue_fallsBackToAdditive() {
        // Directly write an unrecognised enum name to simulate a backup from a future app version.
        sharedPrefs.edit().putString(TallyPreferences.KEY_PERCENT_MODE, "UNKNOWN_MODE").commit()

        val result = prefs.percentMode

        assertEquals(
            "Unrecognised PercentMode storage value must fall back to ADDITIVE",
            PercentMode.ADDITIVE,
            result,
        )
    }

    @Test
    fun percentMode_additive_roundtrip() {
        prefs.percentMode = PercentMode.ADDITIVE
        assertEquals(PercentMode.ADDITIVE, prefs.percentMode)
    }

    @Test
    fun percentMode_direct_roundtrip() {
        prefs.percentMode = PercentMode.DIRECT
        assertEquals(PercentMode.DIRECT, prefs.percentMode)
    }

    // ── insertExactValue ──────────────────────────────────────────────────────

    @Test
    fun insertExactValue_defaultIsFalse() {
        assertFalse("insertExactValue must default to false", prefs.insertExactValue)
    }

    @Test
    fun insertExactValue_roundtrip() {
        prefs.insertExactValue = true
        assertTrue(prefs.insertExactValue)

        prefs.insertExactValue = false
        assertFalse(prefs.insertExactValue)
    }

    // ── formFactorKey (T4.6) ──────────────────────────────────────────────────

    @Test
    fun formFactorKey_defaultIsNormal() {
        assertEquals(TallyPreferences.DEFAULT_FORM_FACTOR, prefs.formFactorKey)
    }

    @Test
    fun formFactorKey_roundtrip() {
        prefs.formFactorKey = "ONE_HANDED"
        assertEquals("ONE_HANDED", prefs.formFactorKey)

        prefs.formFactorKey = "SPLIT"
        assertEquals("SPLIT", prefs.formFactorKey)

        prefs.formFactorKey = "FLOATING"
        assertEquals("FLOATING", prefs.formFactorKey)

        prefs.formFactorKey = "NORMAL"
        assertEquals("NORMAL", prefs.formFactorKey)
    }

    @Test
    fun oneHandedRight_defaultIsFalse() {
        assertFalse(prefs.oneHandedRight)
    }

    @Test
    fun oneHandedRight_roundtrip() {
        prefs.oneHandedRight = true
        assertTrue(prefs.oneHandedRight)
        prefs.oneHandedRight = false
        assertFalse(prefs.oneHandedRight)
    }

    @Test
    fun floatingOffsetX_defaultIsZero() {
        assertEquals(0, prefs.floatingOffsetX)
    }

    @Test
    fun floatingOffsetY_defaultIsZero() {
        assertEquals(0, prefs.floatingOffsetY)
    }

    @Test
    fun floatingOffsets_roundtrip() {
        prefs.floatingOffsetX = 150
        prefs.floatingOffsetY = 300
        assertEquals(150, prefs.floatingOffsetX)
        assertEquals(300, prefs.floatingOffsetY)
    }

    // ── Autocorrect / auto-space (PHASE 1a) ───────────────────────────────────

    @Test
    fun autocorrectEnabled_defaultIsTrue() {
        assertTrue("autocorrect must default to on", prefs.autocorrectEnabled)
    }

    @Test
    fun autocorrectEnabled_roundtrip() {
        prefs.autocorrectEnabled = false
        assertFalse(prefs.autocorrectEnabled)
        prefs.autocorrectEnabled = true
        assertTrue(prefs.autocorrectEnabled)
    }

    @Test
    fun autoSpaceEnabled_defaultIsTrue() {
        assertTrue("auto-space must default to on", prefs.autoSpaceEnabled)
    }

    @Test
    fun autoSpaceEnabled_roundtrip() {
        prefs.autoSpaceEnabled = false
        assertFalse(prefs.autoSpaceEnabled)
        prefs.autoSpaceEnabled = true
        assertTrue(prefs.autoSpaceEnabled)
    }

    // ── Auto-cap / double-space-to-period (PHASE 1b) ──────────────────────────

    @Test
    fun autoCapEnabled_defaultIsTrue() {
        assertTrue("auto-cap must default to on", prefs.autoCapEnabled)
    }

    @Test
    fun autoCapEnabled_roundtrip() {
        prefs.autoCapEnabled = false
        assertFalse(prefs.autoCapEnabled)
        prefs.autoCapEnabled = true
        assertTrue(prefs.autoCapEnabled)
    }

    @Test
    fun doubleSpacePeriod_defaultIsTrue() {
        assertTrue("double-space-to-period must default to on", prefs.doubleSpacePeriod)
    }

    @Test
    fun doubleSpacePeriod_roundtrip() {
        prefs.doubleSpacePeriod = false
        assertFalse(prefs.doubleSpacePeriod)
        prefs.doubleSpacePeriod = true
        assertTrue(prefs.doubleSpacePeriod)
    }
}
