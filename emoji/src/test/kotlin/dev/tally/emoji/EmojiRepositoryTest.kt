package dev.tally.emoji

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmojiRepositoryTest {

    private lateinit var repo: EmojiRepository

    private val sampleCorpus = listOf(
        EmojiEntry("😀", "grinning face", EmojiCategory.SMILEYS_EMOTION, "face grin happy smile"),
        EmojiEntry("😂", "face with tears of joy", EmojiCategory.SMILEYS_EMOTION, "face joy laugh tear"),
        EmojiEntry("🐶", "dog face", EmojiCategory.ANIMALS_NATURE, "dog face pet"),
        EmojiEntry("🍕", "pizza", EmojiCategory.FOOD_DRINK, "cheese pizza slice"),
        EmojiEntry("👋", "waving hand", EmojiCategory.PEOPLE_BODY, "hand wave",
                   listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿")),
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.getSharedPreferences("emoji_recents", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = EmojiRepository.forTest(sampleCorpus, ctx)
    }

    @Test
    fun `forCategory returns only matching entries`() {
        val smileys = repo.forCategory(EmojiCategory.SMILEYS_EMOTION)
        assertEquals(2, smileys.size)
        assertTrue(smileys.all { it.category == EmojiCategory.SMILEYS_EMOTION })
    }

    @Test
    fun `forCategory RECENTS returns empty when no recents`() {
        val recents = repo.forCategory(EmojiCategory.RECENTS)
        assertTrue(recents.isEmpty())
    }

    @Test
    fun `forCategory RECENTS returns recorded entries in recency order`() {
        repo.recents.record("😀")
        repo.recents.record("🍕")
        val recents = repo.forCategory(EmojiCategory.RECENTS)
        assertEquals("🍕", recents.first().emoji)
        assertEquals("😀", recents[1].emoji)
    }

    @Test
    fun `forCategory RECENTS drops unknown emoji silently`() {
        repo.recents.record("👾") // not in sampleCorpus
        repo.recents.record("😀")
        val recents = repo.forCategory(EmojiCategory.RECENTS)
        assertEquals(1, recents.size)
        assertEquals("😀", recents.first().emoji)
    }

    @Test
    fun `search delegates to EmojiSearcher`() {
        val results = repo.search("pizza")
        assertEquals(1, results.size)
        assertEquals("🍕", results.first().emoji)
    }

    @Test
    fun `availableCategories excludes RECENTS when empty`() {
        val cats = repo.availableCategories()
        assertFalse(cats.contains(EmojiCategory.RECENTS))
    }

    @Test
    fun `availableCategories includes RECENTS after recording`() {
        repo.recents.record("😀")
        val cats = repo.availableCategories()
        assertTrue(cats.contains(EmojiCategory.RECENTS))
    }

    @Test
    fun `totalCount matches corpus size`() {
        assertEquals(sampleCorpus.size, repo.totalCount())
    }
}
