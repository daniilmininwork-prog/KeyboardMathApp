package dev.tally.emoji

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsStoreTest {

    private lateinit var store: RecentsStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // Clear any state left over from previous tests.
        ctx.getSharedPreferences("emoji_recents", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = RecentsStore(ctx)
    }

    @Test
    fun `empty store returns empty list`() {
        assertTrue(store.getRecents().isEmpty())
    }

    @Test
    fun `single record is retrievable`() {
        store.record("😀")
        assertEquals(listOf("😀"), store.getRecents())
    }

    @Test
    fun `most recent appears first`() {
        store.record("😀")
        store.record("🚀")
        assertEquals("🚀", store.getRecents().first())
        assertEquals("😀", store.getRecents()[1])
    }

    @Test
    fun `duplicate is promoted to front without growing list`() {
        store.record("😀")
        store.record("🚀")
        store.record("😀")
        val recents = store.getRecents()
        assertEquals(listOf("😀", "🚀"), recents)
    }

    @Test
    fun `capped at MAX_RECENTS entries`() {
        repeat(RecentsStore.MAX_RECENTS + 5) { i ->
            store.record("emoji-$i")
        }
        assertEquals(RecentsStore.MAX_RECENTS, store.getRecents().size)
    }

    @Test
    fun `clear removes all recents`() {
        store.record("😀")
        store.record("🚀")
        store.clear()
        assertTrue(store.getRecents().isEmpty())
    }

    @Test
    fun `blank emoji is not recorded`() {
        store.record("")
        store.record("  ")
        assertTrue(store.getRecents().isEmpty())
    }

    @Test
    fun `recents survive across store instances`() {
        store.record("😂")
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val store2 = RecentsStore(ctx)
        assertEquals(listOf("😂"), store2.getRecents())
    }
}
