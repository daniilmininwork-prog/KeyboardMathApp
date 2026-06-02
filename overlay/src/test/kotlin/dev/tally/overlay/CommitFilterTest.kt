package dev.tally.overlay

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CommitFilter].
 *
 * These tests run on Robolectric (API 33 baseline) to exercise the pre-API-37 code path —
 * i.e., the degraded "pass all events" mode. The API 37 code path (reflection-based
 * getTextChangeTypes) is not covered here because Robolectric does not simulate API 37
 * semantics; that path is exercised by instrumentation tests on real API 37+ hardware.
 *
 * TO.2 acceptance criteria covered:
 *   - Selection-change events are always passed through (commit signal).
 *   - On pre-API-37 devices all text-change events pass through (degrade gracefully).
 *   - Filter never throws for any input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommitFilterTest {

    // ── Selection-change events ───────────────────────────────────────────────

    @Test
    fun `selection change event is always a commit signal`() {
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        assertTrue(CommitFilter.shouldEvaluate(event))
    }

    // ── Text-change events on pre-API-37 (pass-all degraded mode) ────────────

    @Test
    fun `text change event passes through on pre-37 device`() {
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        // On API 33 (Robolectric default) there is no getTextChangeTypes; the filter degrades
        // to pass-all so the evaluator sees every text-change event.
        assertTrue(CommitFilter.shouldEvaluate(event))
    }

    @Test
    fun `focus event is not filtered by CommitFilter`() {
        // Focus events are handled by a different branch in handleTextEvent and are not
        // routed through CommitFilter, but shouldEvaluate must not throw if called with one.
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        // Return value is irrelevant; the important guarantee is no exception.
        CommitFilter.shouldEvaluate(event)
    }

    // ── Null-safety / degenerate inputs ──────────────────────────────────────

    @Test
    fun `shouldEvaluate never throws for an empty text-changed event`() {
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        // Must return without throwing regardless of event contents.
        CommitFilter.shouldEvaluate(event)
    }

    @Test
    fun `shouldEvaluate never throws for windows-changed event`() {
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        CommitFilter.shouldEvaluate(event)
    }
}
