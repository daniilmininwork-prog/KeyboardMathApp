package dev.tally.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [ClipboardInserter].
 *
 * Phase 7 acceptance criteria: "insertion works and restores clipboard".
 *
 * Tests use Robolectric's real ClipboardManager shadow and idle the Looper to
 * simulate the restore delay without real time passing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardInserterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    /** A no-op node — we only care about clipboard behaviour here, not the paste action. */
    private val fakeNode: AccessibilityNodeInfo = AccessibilityNodeInfo.obtain()

    @Test
    fun `clipboard is set to result text before restore fires`() {
        val inserter = ClipboardInserter(clipboard, restoreDelayMs = 1000L)
        clipboard.setPrimaryClip(ClipData.newPlainText("old", "old content"))

        inserter.insert(fakeNode, "42")

        // Restore has NOT fired yet (delay hasn't elapsed) — clipboard should hold "42".
        assertEquals("42", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `previous clipboard is restored after delay`() {
        val inserter = ClipboardInserter(clipboard, restoreDelayMs = 200L)
        clipboard.setPrimaryClip(ClipData.newPlainText("old", "old content"))

        inserter.insert(fakeNode, "42")

        // Advance the main looper past the restore delay.
        shadowOf(Looper.getMainLooper()).idleFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals("old content", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `null previous clipboard is handled without crashing on API 28 plus`() {
        // Start with an empty clipboard (no prior clip set in this test).
        val inserter = ClipboardInserter(clipboard, restoreDelayMs = 50L)

        // Ensure clipboard starts empty in this test instance.
        // (Robolectric ClipboardManager starts with no primary clip.)
        assertNull(clipboard.primaryClip)

        inserter.insert(fakeNode, "result")

        // Must not throw during or after the restore.
        shadowOf(Looper.getMainLooper()).idleFor(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Test
    fun `multiple inserts each restore their own snapshot`() {
        val inserter = ClipboardInserter(clipboard, restoreDelayMs = 200L)

        clipboard.setPrimaryClip(ClipData.newPlainText("", "first"))
        inserter.insert(fakeNode, "4")

        // Simulate the restore of the first insert before the second one.
        shadowOf(Looper.getMainLooper()).idleFor(300L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("first", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        clipboard.setPrimaryClip(ClipData.newPlainText("", "second"))
        inserter.insert(fakeNode, "8")
        shadowOf(Looper.getMainLooper()).idleFor(300L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("second", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
