package dev.tally.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearcherTest {

    private val corpus = listOf(
        EmojiEntry("😀", "grinning face", EmojiCategory.SMILEYS_EMOTION, "face grin happy smile"),
        EmojiEntry("😂", "face with tears of joy", EmojiCategory.SMILEYS_EMOTION, "face joy laugh tear"),
        EmojiEntry("🐶", "dog face", EmojiCategory.ANIMALS_NATURE, "dog face pet"),
        EmojiEntry("🍕", "pizza", EmojiCategory.FOOD_DRINK, "cheese pizza slice"),
        EmojiEntry("🚀", "rocket", EmojiCategory.TRAVEL_PLACES, "rocket space"),
        EmojiEntry("⚽", "soccer ball", EmojiCategory.ACTIVITIES, "ball football soccer"),
        EmojiEntry("💡", "light bulb", EmojiCategory.OBJECTS, "bulb comic idea light"),
        EmojiEntry("❤️", "red heart", EmojiCategory.SYMBOLS, "heart red"),
        EmojiEntry("👋", "waving hand", EmojiCategory.PEOPLE_BODY, "hand wave",
                   listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿")),
    )

    private val searcher = EmojiSearcher(corpus)

    @Test
    fun `blank query returns empty list`() {
        assertTrue(searcher.search("").isEmpty())
        assertTrue(searcher.search("   ").isEmpty())
    }

    @Test
    fun `single-character query returns empty list`() {
        assertTrue(searcher.search("f").isEmpty())
        assertTrue(searcher.search("a").isEmpty())
    }

    @Test
    fun `exact name match ranks first`() {
        val results = searcher.search("pizza")
        assertEquals("🍕", results.first().emoji)
    }

    @Test
    fun `prefix query matches keyword`() {
        val results = searcher.search("roc")
        assertEquals(1, results.size)
        assertEquals("🚀", results.first().emoji)
    }

    @Test
    fun `multi-token query narrows results`() {
        // "face" matches multiple entries; "grin" further narrows to just the grinning face
        val results = searcher.search("grin face")
        assertEquals(1, results.size)
        assertEquals("😀", results.first().emoji)
    }

    @Test
    fun `query with no match returns empty list`() {
        val results = searcher.search("xyzzy")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `case-insensitive matching works`() {
        val results = searcher.search("PIZZA")
        assertEquals(1, results.size)
        assertEquals("🍕", results.first().emoji)
    }

    @Test
    fun `maxResults cap is respected`() {
        // "face" appears in several entries; cap to 2
        val results = searcher.search("face", maxResults = 2)
        assertTrue(results.size <= 2)
    }

    @Test
    fun `keyword-only match is found`() {
        // "soccer" is a keyword, not in the name "soccer ball"
        val results = searcher.search("soccer")
        assertTrue(results.any { it.emoji == "⚽" })
    }
}
