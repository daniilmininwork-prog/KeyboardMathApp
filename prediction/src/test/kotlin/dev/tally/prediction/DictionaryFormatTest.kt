package dev.tally.prediction

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Tests that verify the binary dictionary format constants, the in-memory
 * EnglishDictionary model, and the parsing path through DictionaryLoader.
 *
 * These tests run on the JVM without an Android emulator — DictionaryLoader.parseFrom
 * accepts a raw InputStream so no AssetManager is needed.
 */
class DictionaryFormatTest : FreeSpec({

    // ── Format constants ──────────────────────────────────────────────────────

    "magic is exactly 10 ASCII bytes" {
        DictionaryFormat.MAGIC.size shouldBe 10
        DictionaryFormat.MAGIC.toString(Charsets.US_ASCII) shouldBe "TALLY_DICT"
    }

    "VERSION is 1" {
        DictionaryFormat.VERSION shouldBe 1.toShort()
    }

    "HEADER_BYTES = magic(10) + version(2) + wordCount(4) + bigramCount(4)" {
        DictionaryFormat.HEADER_BYTES shouldBe 20
    }

    "MAX_WORD_BYTES fits in a single byte length prefix" {
        DictionaryFormat.MAX_WORD_BYTES shouldBe 255
    }

    "BIGRAM_ENTRY_BYTES = w1(3) + w2(3) + logProb(4)" {
        DictionaryFormat.BIGRAM_ENTRY_BYTES shouldBe 10
    }

    // ── EnglishDictionary model ───────────────────────────────────────────────

    "empty dictionary has zero counts and returns UNSEEN_LOG_PROB for all queries" {
        val dict = emptyDict()
        dict.wordCount   shouldBe 0
        dict.bigramCount shouldBe 0
        dict.indexOf("hello")             shouldBe -1
        dict.unigramLogProb("hello")      shouldBe EnglishDictionary.UNSEEN_LOG_PROB
        dict.bigramLogProb("foo", "bar")  shouldBe EnglishDictionary.UNSEEN_LOG_PROB
    }

    "indexOf uses binary search on sorted words" {
        val dict = EnglishDictionary(
            words           = arrayOf("a", "be", "can", "do", "eat"),
            unigramLogProbs = floatArrayOf(-1f, -2f, -3f, -4f, -5f),
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
        dict.indexOf("a")   shouldBe 0
        dict.indexOf("be")  shouldBe 1
        dict.indexOf("can") shouldBe 2
        dict.indexOf("do")  shouldBe 3
        dict.indexOf("eat") shouldBe 4
        dict.indexOf("zoo") shouldBe -1
        dict.indexOf("")    shouldBe -1
    }

    "unigramLogProb returns stored value for known words" {
        val dict = EnglishDictionary(
            words           = arrayOf("hello", "world"),
            unigramLogProbs = floatArrayOf(-3.5f, -4.2f),
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
        dict.unigramLogProb("hello") shouldBe -3.5f
        dict.unigramLogProb("world") shouldBe -4.2f
    }

    "unigramLogProb returns UNSEEN_LOG_PROB for unknown words" {
        val dict = EnglishDictionary(
            words           = arrayOf("hello"),
            unigramLogProbs = floatArrayOf(-3.5f),
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
        dict.unigramLogProb("xyz") shouldBe EnglishDictionary.UNSEEN_LOG_PROB
    }

    "bigramLogProb returns stored value when pair exists" {
        val dict = EnglishDictionary(
            words           = arrayOf("a", "b", "c"),
            unigramLogProbs = floatArrayOf(-2f, -3f, -4f),
            bigramW1        = intArrayOf(0, 1),
            bigramW2        = intArrayOf(1, 2),
            bigramLogProbs  = floatArrayOf(-5f, -6f),
        )
        dict.bigramLogProb("a", "b") shouldBe -5f
        dict.bigramLogProb("b", "c") shouldBe -6f
    }

    "bigramLogProb falls back to unigram when pair absent" {
        val dict = EnglishDictionary(
            words           = arrayOf("a", "b"),
            unigramLogProbs = floatArrayOf(-2f, -3f),
            bigramW1        = intArrayOf(0),
            bigramW2        = intArrayOf(1),
            bigramLogProbs  = floatArrayOf(-5f),
        )
        // (b,a) not stored → unigram for "a"
        dict.bigramLogProb("b", "a") shouldBe -2f
    }

    "bigramLogProb backs off correctly for unknown words" {
        val dict = EnglishDictionary(
            words           = arrayOf("a", "b"),
            unigramLogProbs = floatArrayOf(-2f, -3f),
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
        // prev unknown, next known → back off to unigram of "b"
        dict.bigramLogProb("unknown", "b") shouldBe -3f
        // prev known, next unknown → UNSEEN_LOG_PROB (via unigram back-off)
        dict.bigramLogProb("a", "unknown") shouldBe EnglishDictionary.UNSEEN_LOG_PROB
        // both unknown → UNSEEN_LOG_PROB
        dict.bigramLogProb("x", "y") shouldBe EnglishDictionary.UNSEEN_LOG_PROB
    }

    "UNSEEN_LOG_PROB is negative" {
        (EnglishDictionary.UNSEEN_LOG_PROB < 0f) shouldBe true
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    "parser reads a single-word dictionary correctly" {
        val dict = validDict {
            writeInt(1); writeInt(0)
            val b = "hello".toByteArray(Charsets.UTF_8)
            writeByte(b.size); write(b); writeInt(500)
        }
        dict.wordCount   shouldBe 1
        dict.bigramCount shouldBe 0
        dict.indexOf("hello") shouldBe 0
        dict.unigramLogProb("hello") shouldNotBe EnglishDictionary.UNSEEN_LOG_PROB
    }

    "parser rejects wrong magic" {
        val raw = bytes {
            write("WRONG_DICT".toByteArray(Charsets.US_ASCII))
            writeShort(1); writeInt(0); writeInt(0)
        }
        var caught = false
        try { DictionaryLoader.parseFrom(ByteArrayInputStream(raw)) } catch (e: IOException) { caught = true }
        caught shouldBe true
    }

    "parser rejects unsupported version" {
        val raw = bytes {
            write("TALLY_DICT".toByteArray(Charsets.US_ASCII))
            writeShort(99); writeInt(0); writeInt(0)
        }
        var caught = false
        try { DictionaryLoader.parseFrom(ByteArrayInputStream(raw)) } catch (e: IOException) { caught = true }
        caught shouldBe true
    }

    "parser handles a dictionary with bigrams" {
        val dict = validDict {
            writeInt(2); writeInt(1)
            writeByte(1); write("a".toByteArray()); writeInt(1000)
            writeByte(1); write("b".toByteArray()); writeInt(500)
            writeByte(0); writeByte(0); writeByte(0)  // w1 = 0
            writeByte(0); writeByte(0); writeByte(1)  // w2 = 1
            writeFloat(-2.5f)
        }
        dict.wordCount   shouldBe 2
        dict.bigramCount shouldBe 1
        dict.bigramLogProb("a", "b") shouldBe -2.5f
    }

    // ── Frequency smoothing ───────────────────────────────────────────────────

    "all smoothed unigram log-probabilities are negative" {
        val dict = validDict {
            writeInt(3); writeInt(0)
            for ((w, f) in listOf("a" to 1000, "b" to 100, "c" to 10)) {
                val b = w.toByteArray(); writeByte(b.size); write(b); writeInt(f)
            }
        }
        dict.unigramLogProbs.all { it < 0f } shouldBe true
    }

    "higher-frequency word gets higher (less negative) log-probability" {
        val dict = validDict {
            writeInt(2); writeInt(0)
            val c = "common".toByteArray(); writeByte(c.size); write(c); writeInt(100000)
            val r = "rare".toByteArray();   writeByte(r.size); write(r); writeInt(1)
        }
        (dict.unigramLogProb("common") > dict.unigramLogProb("rare")) shouldBe true
    }
})

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun emptyDict() = EnglishDictionary(
    words           = emptyArray(),
    unigramLogProbs = floatArrayOf(),
    bigramW1        = intArrayOf(),
    bigramW2        = intArrayOf(),
    bigramLogProbs  = floatArrayOf(),
)

/** Builds a valid .tally-dict stream; [body] writes word-count, bigram-count, and payload. */
private fun validDict(body: DataOutputStream.() -> Unit): EnglishDictionary {
    val raw = bytes {
        write("TALLY_DICT".toByteArray(Charsets.US_ASCII))
        writeShort(1)
        body()
    }
    return DictionaryLoader.parseFrom(ByteArrayInputStream(raw))
}

private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).block()
    return out.toByteArray()
}
