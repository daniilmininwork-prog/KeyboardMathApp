package dev.tally.emoji

import android.content.Context
import android.content.SharedPreferences

/**
 * Local-only store for recently used emoji.
 *
 * Persistence is via a private [SharedPreferences] file. Recents are stored as a
 * pipe-separated string to avoid the overhead of a full JSON round-trip on every
 * access; the list is small (≤ [MAX_RECENTS] entries) so linear operations are fine.
 *
 * Privacy: this store is never read by or transmitted to any remote endpoint. It lives
 * entirely on-device in the app's private data directory.
 *
 * @param context  Application context (used to open the preferences file).
 */
class RecentsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the most recently used emoji, newest first.
     *
     * The list never exceeds [MAX_RECENTS] entries.
     */
    fun getRecents(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    /**
     * Records [emoji] as the most recently used, promoting it to the front.
     *
     * Duplicate entries are collapsed: if [emoji] was already in the list it moves to front
     * rather than appearing twice. The list is capped at [MAX_RECENTS] entries.
     */
    fun record(emoji: String) {
        if (emoji.isBlank()) return
        val current = getRecents().toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        val trimmed = current.take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, trimmed.joinToString(SEPARATOR)).apply()
    }

    /**
     * Clears the entire recents list.
     */
    fun clear() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    companion object {
        const val MAX_RECENTS = 30
        private const val PREFS_NAME  = "emoji_recents"
        private const val KEY_RECENTS = "recents"
        private const val SEPARATOR   = "|"
    }
}
