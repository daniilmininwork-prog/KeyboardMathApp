package dev.tally.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PlatformCapabilities].
 *
 * Verifies that each SDK gate property returns the correct value for the SDK level
 * declared in the test's [Config]. The gate values are derived from
 * 05-device-framework-compatibility.md §1.2.
 *
 * Each SDK-gated test uses a separate [Config] to pin the Build.VERSION.SDK_INT that
 * Robolectric reports. API 35 is the highest level Robolectric supports with this
 * version of the SDK; gates at 36 are verified by the false-path test at 35.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformCapabilitiesTest {

    // ── hasFoldingFeature: always true regardless of SDK ─────────────────────

    @Config(sdk = [26])
    @Test
    fun `hasFoldingFeature is true at minSdk 26 via Jetpack backport`() {
        assertTrue(PlatformCapabilities.hasFoldingFeature)
    }

    @Config(sdk = [33])
    @Test
    fun `hasFoldingFeature is true at API 33`() {
        assertTrue(PlatformCapabilities.hasFoldingFeature)
    }

    // ── hasSurroundingText: true at API 31+, false below ─────────────────────

    @Config(sdk = [31])
    @Test
    fun `hasSurroundingText is true at API 31`() {
        assertTrue(PlatformCapabilities.hasSurroundingText)
    }

    @Config(sdk = [30])
    @Test
    fun `hasSurroundingText is false below API 31`() {
        assertFalse(PlatformCapabilities.hasSurroundingText)
    }

    // ── hasMultiDisplayIme: true at API 29+, false below ─────────────────────

    @Config(sdk = [29])
    @Test
    fun `hasMultiDisplayIme is true at API 29`() {
        assertTrue(PlatformCapabilities.hasMultiDisplayIme)
    }

    @Config(sdk = [28])
    @Test
    fun `hasMultiDisplayIme is false at API 28`() {
        assertFalse(PlatformCapabilities.hasMultiDisplayIme)
    }

    // ── hasInlineAutofill: true at API 30+, false below ──────────────────────

    @Config(sdk = [30])
    @Test
    fun `hasInlineAutofill is true at API 30`() {
        assertTrue(PlatformCapabilities.hasInlineAutofill)
    }

    @Config(sdk = [29])
    @Test
    fun `hasInlineAutofill is false at API 29`() {
        assertFalse(PlatformCapabilities.hasInlineAutofill)
    }

    // ── hasInsetAnimation: true at API 30+, false below ──────────────────────

    @Config(sdk = [30])
    @Test
    fun `hasInsetAnimation is true at API 30`() {
        assertTrue(PlatformCapabilities.hasInsetAnimation)
    }

    @Config(sdk = [29])
    @Test
    fun `hasInsetAnimation is false at API 29`() {
        assertFalse(PlatformCapabilities.hasInsetAnimation)
    }

    // ── hasStylusHandwriting: true at API 34+, false below ───────────────────

    @Config(sdk = [34])
    @Test
    fun `hasStylusHandwriting is true at API 34`() {
        assertTrue(PlatformCapabilities.hasStylusHandwriting)
    }

    @Config(sdk = [33])
    @Test
    fun `hasStylusHandwriting is false at API 33`() {
        assertFalse(PlatformCapabilities.hasStylusHandwriting)
    }

    // ── hasA11yTextChangeType: false at API 35 (gate is SDK 36) ──────────────
    //
    // Robolectric does not support SDK 36 in the current build setup, so only the
    // false-path (below the gate) is covered here. The true-path (>= 36) is verified
    // structurally: the gate constant is 36, and the implementation uses >= 36, so
    // any SDK at or above 36 will return true by construction.

    @Config(sdk = [35])
    @Test
    fun `hasA11yTextChangeType is false at API 35 (gate is 36)`() {
        assertFalse(PlatformCapabilities.hasA11yTextChangeType)
    }

    // ── Logical consistency checks ────────────────────────────────────────────

    /**
     * If hasSurroundingText is available (API 31+), hasInlineAutofill must also be
     * available (API 30+). This invariant is true because 31 >= 30.
     */
    @Config(sdk = [31])
    @Test
    fun `at API 31 both hasSurroundingText and hasInlineAutofill are true`() {
        assertTrue(PlatformCapabilities.hasSurroundingText)
        assertTrue(PlatformCapabilities.hasInlineAutofill)
    }

    /**
     * At API 29, hasMultiDisplayIme is available but hasInlineAutofill is not.
     */
    @Config(sdk = [29])
    @Test
    fun `at API 29 hasMultiDisplayIme is true but hasInlineAutofill is false`() {
        assertTrue(PlatformCapabilities.hasMultiDisplayIme)
        assertFalse(PlatformCapabilities.hasInlineAutofill)
    }

    /**
     * At API 34, stylus handwriting is available but the a11y text-change type is not.
     */
    @Config(sdk = [34])
    @Test
    fun `at API 34 hasStylusHandwriting is true but hasA11yTextChangeType is false`() {
        assertTrue(PlatformCapabilities.hasStylusHandwriting)
        assertFalse(PlatformCapabilities.hasA11yTextChangeType)
    }
}
