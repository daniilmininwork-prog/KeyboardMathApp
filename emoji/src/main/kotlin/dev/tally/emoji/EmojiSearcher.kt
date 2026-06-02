package dev.tally.emoji

/**
 * In-memory keyword search over a loaded emoji dataset.
 *
 * Search is case-insensitive and prefix-based per token: a query of "smi fac" matches any
 * emoji whose name or keyword string contains a token starting with "smi" AND a token starting
 * with "fac". Single-character queries are ignored to avoid flooding results.
 *
 * Results are ranked by relevance:
 *   1. Exact name match (highest priority).
 *   2. Name starts-with the full query.
 *   3. Any keyword token starts-with the full query.
 *   4. Multi-token prefix matches (lowest priority).
 *
 * All matching runs in O(n × k) with n = corpus size and k = query token count. For the
 * bundled ~450-entry dataset this finishes well under 1 ms even without indexing.
 */
class EmojiSearcher(private val corpus: List<EmojiEntry>) {

    /**
     * Returns up to [maxResults] emoji entries matching [query].
     *
     * Returns an empty list for blank or single-character queries.
     */
    fun search(query: String, maxResults: Int = 50): List<EmojiEntry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()

        val tokens = q.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()

        data class Scored(val entry: EmojiEntry, val score: Int)

        val results = mutableListOf<Scored>()
        for (entry in corpus) {
            val score = scoreEntry(entry, q, tokens)
            if (score > 0) results += Scored(entry, score)
        }

        return results
            .sortedByDescending { it.score }
            .take(maxResults)
            .map { it.entry }
    }

    private fun scoreEntry(entry: EmojiEntry, fullQuery: String, tokens: List<String>): Int {
        val name = entry.name.lowercase()
        val kwds = entry.keywords.lowercase()

        // Exact name match is the top tier.
        if (name == fullQuery) return 1000

        // Name starts with the full query string.
        if (name.startsWith(fullQuery)) return 500

        // Full query appears anywhere in the name or keywords.
        if (name.contains(fullQuery) || kwds.contains(fullQuery)) return 200

        // All query tokens must appear as a prefix of some word in name or keywords combined.
        val nameTokens = name.split(" ")
        val kwdTokens  = kwds.split(" ")
        val allTokens  = nameTokens + kwdTokens

        val allMatch = tokens.all { tok ->
            allTokens.any { it.startsWith(tok) }
        }
        return if (allMatch) 50 else 0
    }
}
