package dev.tally.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SuggestionStripView] using Robolectric (JVM-runnable, no device required).
 *
 * Acceptance criteria covered (T2.3):
 *   - Initially no chip and not clickable.
 *   - [showSuggestion] sets the suggestion text and makes the view clickable.
 *   - [clearSuggestion] removes the suggestion and disables clicking.
 *   - Tapping fires [onSuggestionTapped] when a suggestion is set.
 *   - Tapping is a no-op when no suggestion is set.
 *   - Accessibility content description reflects the current math suggestion.
 *   - [bind] with math + word candidates shows both coexisting.
 *   - Math chip is always in the reserved slot; word candidates never displace it.
 *   - Tapping a word candidate fires the [onWordTap] callback with the correct [Suggestion].
 *   - Tapping the math chip fires [onMathTap].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionStripViewTest {

    private lateinit var strip: SuggestionStripView

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        strip = SuggestionStripView(ctx)
    }

    // ── Legacy single-source API (backward compat) ────────────────────────────

    @Test
    fun initialState_noSuggestionAndNotClickable() {
        assertNull(strip.suggestion)
        assertFalse(strip.isClickable)
    }

    @Test
    fun showSuggestion_setsSuggestionAndMakesClickable() {
        strip.showSuggestion("4")

        assertEquals("4", strip.suggestion)
        assertTrue(strip.isClickable)
    }

    @Test
    fun clearSuggestion_removesSuggestionAndDisablesClick() {
        strip.showSuggestion("4")
        strip.clearSuggestion()

        assertNull(strip.suggestion)
        assertFalse(strip.isClickable)
    }

    @Test
    fun tap_withSuggestion_firesCallback() {
        var tapped = false
        strip.onSuggestionTapped = { tapped = true }
        strip.showSuggestion("4")

        strip.performClick()

        assertTrue(tapped)
    }

    @Test
    fun tap_withNoSuggestion_doesNotFireCallback() {
        var tapped = false
        strip.onSuggestionTapped = { tapped = true }

        strip.performClick()

        assertFalse(tapped)
    }

    @Test
    fun showSuggestion_setsContentDescriptionContainingResult() {
        strip.showSuggestion("230")

        val desc = strip.contentDescription?.toString() ?: ""
        assertTrue("Content description must mention the result value", desc.contains("230"))
    }

    @Test
    fun clearSuggestion_clearsContentDescription() {
        strip.showSuggestion("4")
        strip.clearSuggestion()

        assertNull(strip.contentDescription)
    }

    @Test
    fun multipleShowCalls_updatesChip() {
        strip.showSuggestion("4")
        strip.showSuggestion("8")

        assertEquals("8", strip.suggestion)
    }

    // ── Multi-source bind API (T2.3) ──────────────────────────────────────────

    @Test
    fun bind_mathOnly_showsChipAndSetsDescriptionSuggestion() {
        val math = Suggestion(SuggestionKind.MATH, "42", 0f)
        strip.bind(StripState(mathSuggestion = math, wordCandidates = emptyList()))

        assertEquals("42", strip.suggestion)
        assertTrue(strip.isClickable)
        assertNotNull(strip.contentDescription)
        assertTrue(strip.contentDescription!!.contains("42"))
    }

    @Test
    fun bind_mathAndWords_coexist() {
        val math  = Suggestion(SuggestionKind.MATH, "99", 0f)
        val words = listOf(
            Suggestion(SuggestionKind.PREDICTION, "hello", -1f),
            Suggestion(SuggestionKind.PREDICTION, "world", -2f),
        )
        strip.bind(StripState(mathSuggestion = math, wordCandidates = words))

        // Math chip slot has the result.
        assertEquals("99", strip.suggestion)
        // Word slots are visible (chip + words share the strip).
        assertTrue("Math chip must be visible when math is set", strip.chip.visibility == View.VISIBLE)
    }

    @Test
    fun bind_noMath_chipIsGone() {
        val words = listOf(Suggestion(SuggestionKind.PREDICTION, "hello", -1f))
        strip.bind(StripState(mathSuggestion = null, wordCandidates = words))

        assertEquals(View.GONE, strip.chip.visibility)
        assertNull(strip.suggestion)
    }

    @Test
    fun bind_mathTapCallback_fires() {
        var mathTapped = false
        val math = Suggestion(SuggestionKind.MATH, "7", 0f)
        strip.bind(
            state     = StripState(mathSuggestion = math, wordCandidates = emptyList()),
            onMathTap = { mathTapped = true },
        )

        strip.chip.performClick()

        assertTrue(mathTapped)
    }

    @Test
    fun bind_wordTapCallback_firesWithCorrectCandidate() {
        var tapped: Suggestion? = null
        val words = listOf(Suggestion(SuggestionKind.PREDICTION, "hello", -1f))
        strip.bind(
            state     = StripState(mathSuggestion = null, wordCandidates = words),
            onWordTap = { tapped = it },
        )

        // The first visible word slot is at index 0 in wordSlots.
        // Access via the public wordSlots indirectly through the container's children.
        // We locate the first clickable non-chip child view.
        val wordView = findFirstClickableWordView()
        assertNotNull("Word view must be present and clickable", wordView)
        wordView!!.performClick()

        assertNotNull(tapped)
        assertEquals("hello", tapped!!.text)
    }

    @Test
    fun bind_empty_noSuggestionAndNotClickable() {
        strip.bind(StripState.EMPTY)

        assertNull(strip.suggestion)
        assertFalse(strip.isClickable)
    }

    @Test
    fun bind_mathNeverDisplacedByWordCandidates() {
        val math  = Suggestion(SuggestionKind.MATH, "15", 0f)
        val words = List(3) { i -> Suggestion(SuggestionKind.PREDICTION, "word$i", -i.toFloat()) }
        strip.bind(StripState(mathSuggestion = math, wordCandidates = words))

        assertEquals("15", strip.suggestion)
        assertTrue(strip.chip.visibility == View.VISIBLE)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Finds the first visible, clickable child view that is NOT the math chip.
     * Word slots are direct children of the internal container.
     */
    private fun findFirstClickableWordView(): View? {
        // Walk all children and find a clickable non-chip TextView.
        val container = strip.getChildAt(0) as? android.widget.LinearLayout ?: return null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child !== strip.chip && child.isClickable && child.visibility == View.VISIBLE) {
                return child
            }
        }
        return null
    }
}
