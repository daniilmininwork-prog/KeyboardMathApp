package dev.tally.prediction

import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.ResolvedKey
import kotlin.math.log2

/**
 * Shared helpers for decoder unit tests.
 */
internal object DecoderTestHelper {

    /**
     * Builds a small [EnglishDictionary] with the given (word, rawFrequency) pairs.
     * Words are sorted alphabetically; unigram log-probs computed with KN discount.
     */
    fun dict(vararg entries: Pair<String, Int>): EnglishDictionary {
        val sorted = entries.sortedBy { it.first }
        val words = sorted.map { it.first }.toTypedArray()
        val freqs = sorted.map { it.second }.toIntArray()

        val discount  = 0.75f
        val total     = freqs.map { it.toLong().coerceAtLeast(1L) }.sum()
        val logTotal  = log2(total.toFloat())
        val logProbs  = FloatArray(words.size) { i ->
            val adj = (freqs[i].toFloat() - discount).coerceAtLeast(0.5f)
            log2(adj) - logTotal
        }
        return EnglishDictionary(
            words           = words,
            unigramLogProbs = logProbs,
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
    }

    /**
     * Builds a minimal [KeyGeometry] with letter keys laid out in a single row.
     * Key width = 50px, height = 60px; 'a' starts at x=0.
     */
    fun simpleGeometry(letters: String = "abcdefghijklmnopqrstuvwxyz"): KeyGeometry {
        val keyW = 50f
        val keyH = 60f
        val keys = letters.mapIndexed { idx, ch ->
            ResolvedKey(
                id     = KeyId(rowIndex = 0, keyIndex = idx),
                keyDef = KeyDef(code = ch.code, label = ch.toString(), width = 0.1f),
                left   = idx * keyW,
                top    = 0f,
                right  = idx * keyW + keyW,
                bottom = keyH,
            )
        }
        return KeyGeometry(
            viewportWidth  = (letters.length * keyW).toInt(),
            viewportHeight = keyH.toInt(),
            rowHeight      = keyH,
            keys           = keys,
        )
    }
}
