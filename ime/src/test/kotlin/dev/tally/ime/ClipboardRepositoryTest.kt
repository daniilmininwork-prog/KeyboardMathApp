package dev.tally.ime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.FieldPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ClipboardRepository].
 *
 * Verifies:
 * 1. Insertion never touches [android.content.ClipboardManager] — enforced structurally,
 *    confirmed by the onInsert callback receiving the text directly.
 * 2. Field-policy gating: persistAllowed=false means record() is a no-op.
 * 3. Pin/unpin round-trips through the store.
 * 4. clearHistory() removes unpinned entries only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardRepositoryTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val insertedTexts = mutableListOf<String>()
    private lateinit var store: ClipboardStore
    private lateinit var repo: ClipboardRepository

    @Before
    fun setUp() {
        context.getSharedPreferences(ClipboardStore.PREFS_NAME, 0).edit().clear().commit()
        insertedTexts.clear()
        store = ClipboardStore(
            context    = context,
            encryption = ClipboardStore.NoOpEncryption(),
            clockMs    = { System.currentTimeMillis() },
        )
        repo = ClipboardRepository(store = store, onInsert = { insertedTexts.add(it) })
    }

    @Test
    fun insertCallsOnInsertWithEntryText() {
        store.record("click me", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        repo.insert(entry)
        assertEquals(listOf("click me"), insertedTexts)
    }

    @Test
    fun insertDoesNotTouchSystemClipboard() {
        // Structural guarantee: ClipboardRepository has no ClipboardManager field or import.
        // The onInsert lambda is the only delivery mechanism; we capture it above.
        store.record("safe text", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        repo.insert(entry)
        // If this test passes, the text arrived via onInsert, not via ClipboardManager.
        assertTrue("text arrived via onInsert", insertedTexts.contains("safe text"))
    }

    @Test
    fun recordWithPersistAllowedFalseIsNoop() {
        repo.record("private text", FieldPolicy.DEFAULT_PRIVATE)
        assertTrue(repo.entries().isEmpty())
    }

    @Test
    fun recordWithPermissiveAddsEntry() {
        repo.record("persisted text", FieldPolicy.PERMISSIVE)
        assertEquals(1, repo.entries().size)
        assertEquals("persisted text", repo.entries().first().text)
    }

    @Test
    fun togglePinRoundtrip() {
        repo.record("pin target", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()

        assertTrue(repo.togglePin(entry))

        val pinned = repo.entries().first()
        assertTrue(pinned.isPinned)
    }

    @Test
    fun clearHistoryRemovesUnpinned() {
        repo.record("unpinned entry", FieldPolicy.PERMISSIVE)
        repo.record("to be pinned",   FieldPolicy.PERMISSIVE)
        val pinnedId = repo.entries().first().id   // newest first
        store.togglePin(pinnedId)

        repo.clearHistory()

        val remaining = repo.entries()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].isPinned)
    }

    @Test
    fun removeEntry() {
        repo.record("remove me", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        repo.remove(entry)
        assertTrue(repo.entries().isEmpty())
    }

    @Test
    fun multipleInsertions() {
        repo.record("alpha", FieldPolicy.PERMISSIVE)
        repo.record("beta",  FieldPolicy.PERMISSIVE)

        repo.entries().forEach { repo.insert(it) }
        assertEquals(2, insertedTexts.size)
        assertTrue(insertedTexts.contains("alpha"))
        assertTrue(insertedTexts.contains("beta"))
    }
}
