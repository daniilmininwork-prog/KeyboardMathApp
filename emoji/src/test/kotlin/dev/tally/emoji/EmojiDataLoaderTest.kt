package dev.tally.emoji

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [EmojiDataLoader] can parse the bundled asset and that the result
 * satisfies basic structural invariants.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiDataLoaderTest {

    @Test
    fun `loads non-empty corpus from bundled asset`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        assertTrue("Corpus should be non-empty", entries.isNotEmpty())
    }

    @Test
    fun `all entries have non-empty emoji strings`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        assertTrue(entries.all { it.emoji.isNotEmpty() })
    }

    @Test
    fun `all entries have non-empty names`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        assertTrue(entries.all { it.name.isNotEmpty() })
    }

    @Test
    fun `all nine categories are represented`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        val categories = entries.map { it.category }.toSet()
        // RECENTS is not in the data file — it is a runtime-only category
        val dataCategories = EmojiCategory.entries.filter { it != EmojiCategory.RECENTS }.toSet()
        assertEquals(dataCategories, categories)
    }

    @Test
    fun `skin-tone variants are lists`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        val withSkinTones = entries.filter { it.skinTones.isNotEmpty() }
        // At least some emoji support skin tones (e.g. waving hand)
        assertFalse("Expected at least one emoji with skin-tone variants", withSkinTones.isEmpty())
        // Each variant list should have exactly 5 entries (Fitzpatrick modifiers 1F3FB–1F3FF)
        withSkinTones.forEach { entry ->
            assertEquals("Skin tone list must have 5 variants for ${entry.name}",
                5, entry.skinTones.size)
        }
    }

    @Test
    fun `no duplicate emoji strings in corpus`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        val emojis = entries.map { it.emoji }
        assertEquals("Corpus should have no duplicate emoji", emojis.size, emojis.toSet().size)
    }

    @Test
    fun `well-known emoji present`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val entries = EmojiDataLoader.load(ctx.assets)
        val emojis = entries.map { it.emoji }.toSet()
        assertTrue("😀 should be present", "😀" in emojis)
        assertTrue("❤️ should be present", "❤️" in emojis)
        assertTrue("🍕 should be present", "🍕" in emojis)
        assertTrue("✈️ should be present", "✈️" in emojis)
    }
}
