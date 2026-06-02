package dev.tally.emoji

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads the bundled emoji metadata from the JSON asset produced from Unicode CLDR annotations.
 *
 * The loader is stateless — callers cache the result. Loading is synchronous and fast (~5 ms
 * for 456 entries on a mid-tier device); callers that care about startup time may offload to a
 * background thread, but the loader itself makes no threading assumptions.
 *
 * Asset path: `emoji/emoji_data.json` inside the `emoji` module's asset tree.
 *
 * JSON field mapping:
 *   "e" → emoji string
 *   "n" → name
 *   "c" → EmojiCategory name (e.g. "SMILEYS_EMOTION")
 *   "k" → space-separated keywords string
 *   "s" → optional array of skin-tone variant emoji strings
 */
internal object EmojiDataLoader {

    private const val ASSET_PATH = "emoji/emoji_data.json"

    /**
     * Parses the bundled asset and returns all emoji entries.
     *
     * Entries with an unrecognised category are silently skipped so future data additions do
     * not break older builds. Entries missing a required field are also skipped.
     */
    fun load(assets: AssetManager): List<EmojiEntry> {
        val json = assets.open(ASSET_PATH).use { stream ->
            stream.readBytes().decodeToString()
        }
        val array = JSONArray(json)
        val result = mutableListOf<EmojiEntry>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val entry = parseEntry(obj) ?: continue
            result += entry
        }
        return result
    }

    private fun parseEntry(obj: JSONObject): EmojiEntry? {
        val emoji    = obj.optString("e").takeIf { it.isNotEmpty() } ?: return null
        val name     = obj.optString("n").takeIf { it.isNotEmpty() } ?: return null
        val catName  = obj.optString("c").takeIf { it.isNotEmpty() } ?: return null
        val keywords = obj.optString("k")

        val category = try {
            EmojiCategory.valueOf(catName)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val skinTones = buildList {
            val sArray = obj.optJSONArray("s") ?: return@buildList
            for (j in 0 until sArray.length()) {
                val variant = sArray.optString(j)
                if (variant.isNotEmpty()) add(variant)
            }
        }

        return EmojiEntry(
            emoji      = emoji,
            name       = name,
            category   = category,
            keywords   = keywords,
            skinTones  = skinTones,
        )
    }
}
