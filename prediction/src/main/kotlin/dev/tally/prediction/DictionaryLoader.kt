package dev.tally.prediction

import android.content.res.AssetManager
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.log2

/**
 * Loads the compiled English dictionary asset from the APK asset folder on a
 * background thread and returns an [EnglishDictionary].
 *
 * The asset file is `prediction/en_us.tally-dict` inside the AAR/APK.  It is
 * written in the Tally binary dictionary format (see [DictionaryFormat]).
 *
 * Call [load] from a background thread — it reads and parses the entire asset
 * synchronously and must never be called on the main/UI thread.
 */
class DictionaryLoader(private val assets: AssetManager) {

    /**
     * Reads and parses the bundled English dictionary asset.
     *
     * Must be called off the main thread.  Throws [IOException] on corrupt or
     * missing asset data; callers should catch and fall back to an empty lexicon
     * rather than crashing.
     *
     * @throws IOException if the asset is absent or the header magic does not match.
     */
    @Throws(IOException::class)
    fun load(): EnglishDictionary {
        assets.open(ASSET_PATH).use { stream ->
            return parseStream(stream)
        }
    }

    companion object {
        /** Asset path relative to the AAR/APK assets root. */
        const val ASSET_PATH: String = "prediction/en_us.tally-dict"

        /**
         * Parses a dictionary from a raw [InputStream] without needing an [AssetManager].
         *
         * Used by unit tests and the dict-compiler tool; production code calls
         * [DictionaryLoader.load] instead.
         */
        @Throws(IOException::class)
        fun parseFrom(stream: InputStream): EnglishDictionary = parseStream(stream)

        @Throws(IOException::class)
        internal fun parseStream(stream: InputStream): EnglishDictionary {
            val input = DataInputStream(stream.buffered())

            val magic = ByteArray(DictionaryFormat.MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(DictionaryFormat.MAGIC)) {
                throw IOException("Dictionary magic mismatch — asset may be corrupt or wrong version.")
            }

            val version = input.readShort()
            if (version != DictionaryFormat.VERSION) {
                throw IOException("Unsupported dictionary version $version (expected ${DictionaryFormat.VERSION}).")
            }

            val wordCount   = input.readInt()
            val bigramCount = input.readInt()

            if (wordCount < 0 || bigramCount < 0) {
                throw IOException("Dictionary header contains negative count — asset is corrupt.")
            }

            // ── Words ─────────────────────────────────────────────────────────
            val words      = arrayOfNulls<String>(wordCount)
            val rawFreqs   = IntArray(wordCount)
            var totalCount = 0L

            for (i in 0 until wordCount) {
                val len  = input.readUnsignedByte()
                val buf  = ByteArray(len)
                input.readFully(buf)
                val freq  = input.readInt()
                words[i]  = String(buf, Charsets.UTF_8)
                rawFreqs[i] = freq
                totalCount += freq.toLong().coerceAtLeast(1L)
            }

            // Kneser-Ney-style discount: subtract 0.75 before normalising.
            val discount  = 0.75f
            val logTotal  = log2(totalCount.toFloat())
            val unigramLogPs = FloatArray(wordCount) { i ->
                val adjusted = (rawFreqs[i].toFloat() - discount).coerceAtLeast(0.5f)
                log2(adjusted) - logTotal
            }

            // ── Bigrams ───────────────────────────────────────────────────────
            val bW1      = IntArray(bigramCount)
            val bW2      = IntArray(bigramCount)
            val bLogProb = FloatArray(bigramCount)

            for (i in 0 until bigramCount) {
                bW1[i]      = readInt24(input)
                bW2[i]      = readInt24(input)
                bLogProb[i] = input.readFloat()
            }

            @Suppress("UNCHECKED_CAST")
            return EnglishDictionary(
                words           = words as Array<String>,
                unigramLogProbs = unigramLogPs,
                bigramW1        = bW1,
                bigramW2        = bW2,
                bigramLogProbs  = bLogProb,
            )
        }

        private fun readInt24(input: DataInputStream): Int {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val b2 = input.readUnsignedByte()
            return (b0 shl 16) or (b1 shl 8) or b2
        }
    }
}
