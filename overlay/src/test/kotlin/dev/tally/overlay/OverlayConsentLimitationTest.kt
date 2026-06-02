package dev.tally.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies AC-7: the commit-only limitation copy actually **ships** in the overlay setup
 * screen, not merely that the string resource is declared.
 *
 * Inflating [R.layout.activity_overlay_consent] and walking the view tree catches the failure
 * mode where the string exists in `strings.xml` but no layout ever displays it — which is
 * exactly what "documented commit-only expectation" (AC-7) must rule out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverlayConsentLimitationTest {

    @Test
    fun `consent layout displays the commit-only limitation copy`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = LayoutInflater.from(context)
            .inflate(R.layout.activity_overlay_consent, null)

        val expected = context.getString(R.string.overlay_limitation_commit_only)
        val limitationView = root.findViewById<TextView>(R.id.overlay_limitation)

        assertTrue("Limitation copy must be non-empty", expected.isNotBlank())
        assertEquals(
            "The consent screen must render the commit-only limitation string",
            expected,
            limitationView.text.toString(),
        )
    }

    @Test
    fun `commit-only copy is present somewhere in the inflated consent tree`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = LayoutInflater.from(context)
            .inflate(R.layout.activity_overlay_consent, null)
        val expected = context.getString(R.string.overlay_limitation_commit_only)

        assertTrue(
            "Commit-only limitation text must appear in the consent view hierarchy",
            collectTexts(root).any { it == expected },
        )
    }

    private fun collectTexts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text?.toString().orEmpty())
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(collectTexts(view.getChildAt(i)))
        }
    }
}
