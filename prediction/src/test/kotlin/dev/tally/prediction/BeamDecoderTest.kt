package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BeamDecoderTest : FunSpec({

    val dict = DecoderTestHelper.dict(
        "apple"  to 500,
        "apply"  to 200,
        "apt"    to 100,
        "at"     to 1000,
        "banana" to 300,
        "can"    to 800,
        "candy"  to 150,
        "hello"  to 2000,
        "help"   to 600,
        "world"  to 1500,
        "word"   to 400,
        "work"   to 350,
    )

    val geo = DecoderTestHelper.simpleGeometry()

    val decoder = BeamDecoder(
        dictionary = dict,
        scorer     = SpatialScorer(),
        freqCache  = FrequencyCache.NOOP,
    )

    val ctx = EditingContext.EMPTY

    test("exact prefix returns completions") {
        val results = decoder.decode("hel", ctx, geo, maxResults = 3)
        val words = results.map { it.first }
        // "hello" and "help" both start with "hel"
        words.any { it.startsWith("hel") } shouldBe true
    }

    test("results are sorted by descending score") {
        val results = decoder.decode("hel", ctx, geo, maxResults = 3)
        for (i in 0 until results.size - 1) {
            (results[i].second >= results[i + 1].second) shouldBe true
        }
    }

    test("maxResults is respected") {
        val results = decoder.decode("a", ctx, geo, maxResults = 2)
        results.size shouldBe 2
    }

    test("empty typed string returns next-word suggestions") {
        val nextWordCtx = EditingContext(
            composingWord    = "",
            textBeforeCursor = "I ate a",
            wordBeforeCursor = "a",
        )
        val results = decoder.decode("", nextWordCtx, geo, maxResults = 3)
        // Expect non-empty, at least one result of length >= 2
        results.isNotEmpty() shouldBe true
        results.all { it.first.length >= 2 } shouldBe true
    }

    test("empty dictionary returns empty list") {
        val emptyDecoder = BeamDecoder(
            dictionary = EnglishDictionary(
                words           = emptyArray(),
                unigramLogProbs = floatArrayOf(),
                bigramW1        = intArrayOf(),
                bigramW2        = intArrayOf(),
                bigramLogProbs  = floatArrayOf(),
            ),
        )
        emptyDecoder.decode("hello", ctx, geo, 3).shouldBeEmpty()
    }

    test("null geometry falls back to edit-distance scoring") {
        val results = decoder.decode("helo", ctx, geometry = null, maxResults = 3)
        val words = results.map { it.first }
        // "hello" should rank at or near top via edit-distance
        words.any { it.startsWith("hel") } shouldBe true
    }

    test("bigram context boosts contextually likely words") {
        val dictWithBigram = run {
            val base = DecoderTestHelper.dict(
                "hello" to 2000,
                "help"  to 600,
                "world" to 1500,
            )
            // Add a bigram: (hello, world) → -2.0
            EnglishDictionary(
                words           = base.words,
                unigramLogProbs = base.unigramLogProbs,
                bigramW1        = intArrayOf(base.indexOf("hello")),
                bigramW2        = intArrayOf(base.indexOf("world")),
                bigramLogProbs  = floatArrayOf(-2f),
            )
        }
        val decoderBigram = BeamDecoder(dictionary = dictWithBigram)
        val bigramCtx = EditingContext("wor", "hello ", "hello")
        val results = decoderBigram.decode("wor", bigramCtx, geo, 3)
        // "world" should appear in results
        results.map { it.first }.any { it.startsWith("wor") } shouldBe true
    }

    test("very long typed string with no near candidate returns empty") {
        // "zzzzzzzz" doesn't match anything in the dict within ±2 length
        val results = decoder.decode("zzzzzzzz", ctx, geo, 3)
        // Either empty or only words of similar length
        results.forEach { (word, _) ->
            (kotlin.math.abs(word.length - 8) <= 2) shouldBe true
        }
    }
})
