package dev.tally.emoji

import android.content.Context
import android.content.res.AssetManager

/**
 * Central access point for emoji data within a keyboard session.
 *
 * The repository owns the parsed corpus (loaded once from the bundled JSON asset) and
 * delegates category browsing, keyword search, and recents management to the appropriate
 * sub-components.
 *
 * Threading: [load] is synchronous. Callers that want to avoid blocking the main thread
 * should invoke it from a background executor and store the resulting [EmojiRepository] on
 * the main thread once loading is complete. All other methods are main-thread safe after [load].
 */
class EmojiRepository private constructor(
    private val corpus: List<EmojiEntry>,
    private val searcher: EmojiSearcher,
    val recents: RecentsStore,
) {

    /**
     * Returns all emoji in [category].
     *
     * For [EmojiCategory.RECENTS] returns the emoji entries corresponding to the stored
     * recents list, in recents order (newest first). Entries that are no longer in the corpus
     * (e.g. after a data update) are silently dropped.
     */
    fun forCategory(category: EmojiCategory): List<EmojiEntry> {
        if (category == EmojiCategory.RECENTS) {
            val recentEmoji = recents.getRecents()
            val byEmoji = corpus.associateBy { it.emoji }
            return recentEmoji.mapNotNull { byEmoji[it] }
        }
        return corpus.filter { it.category == category }
    }

    /**
     * Searches the corpus for emoji matching [query].
     *
     * Returns an empty list for blank or single-character queries.
     * Results are capped at [maxResults].
     */
    fun search(query: String, maxResults: Int = 50): List<EmojiEntry> =
        searcher.search(query, maxResults)

    /**
     * Returns the categories for which at least one emoji exists (always non-empty after [load]).
     *
     * [EmojiCategory.RECENTS] is included only when [recents] is non-empty, so the category
     * tab is not shown when the user has not yet used any emoji.
     */
    fun availableCategories(): List<EmojiCategory> {
        val populated = EmojiCategory.entries.filter { cat ->
            when (cat) {
                EmojiCategory.RECENTS -> recents.getRecents().isNotEmpty()
                else -> corpus.any { it.category == cat }
            }
        }
        return populated
    }

    /**
     * Returns the total number of emoji in the corpus (excluding recents, which are derived).
     */
    fun totalCount(): Int = corpus.size

    companion object {
        /**
         * Loads the emoji corpus and constructs the repository.
         *
         * Safe to call multiple times; each call parses the asset anew (no singleton).
         * Callers should cache the result for the lifetime of the keyboard session.
         */
        fun load(context: Context): EmojiRepository {
            val corpus  = EmojiDataLoader.load(context.assets)
            val searcher = EmojiSearcher(corpus)
            val recents  = RecentsStore(context)
            return EmojiRepository(corpus, searcher, recents)
        }

        /**
         * Test-only constructor that injects a pre-built corpus and asset manager.
         * Separated from [load] so tests can pass a pre-built list without a real [AssetManager].
         */
        internal fun forTest(corpus: List<EmojiEntry>, context: Context): EmojiRepository {
            val searcher = EmojiSearcher(corpus)
            val recents  = RecentsStore(context)
            return EmojiRepository(corpus, searcher, recents)
        }
    }
}
