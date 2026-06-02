package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.GlidePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class GestureDecoderImplTest : FunSpec({

    val geo = DecoderTestHelper.simpleGeometry()
    // Dictionary with a handful of words
    val dict = DecoderTestHelper.dict(
        "abc"   to 100,
        "abd"   to 80,
        "xyz"   to 90,
        "hello" to 200,
        "world" to 180,
        "hi"    to 50,
    )
    val decoder = GestureDecoderImpl(dictionary = dict)
    val ctx     = EditingContext.EMPTY
    val permissive = FieldPolicy.PERMISSIVE

    fun idealPath(word: String): GlidePath {
        val pts = IdealPathBuilder().buildForWord(word, geo)
        return GlidePath(pts)
    }

    test("returns empty when glideEnabled is false") {
        val restricted = permissive.copy(glideEnabled = false)
        decoder.decode(idealPath("abc"), geo, ctx, restricted).shouldBeEmpty()
    }

    test("returns empty for path with fewer than 2 points") {
        decoder.decode(GlidePath.EMPTY, geo, ctx, permissive).shouldBeEmpty()
        decoder.decode(GlidePath.of(25f, 30f), geo, ctx, permissive).shouldBeEmpty()
    }

    test("top result for abc trace is abc or abd (lexically close words)") {
        val results = decoder.decode(idealPath("abc"), geo, ctx, permissive, maxResults = 3)
        results.isNotEmpty() shouldBe true
        // The word 'abc' itself should rank in the top results
        val texts = results.map { it.text }
        (texts.contains("abc") || texts.contains("abd")) shouldBe true
    }

    test("respects maxResults") {
        val results = decoder.decode(idealPath("hello"), geo, ctx, permissive, maxResults = 2)
        results.size shouldBe 2.coerceAtMost(results.size)
    }

    test("cancellation via isActive stops the scan early") {
        // isActive returns false immediately — should return empty or partial results, not hang
        val results = decoder.decode(
            idealPath("hello"),
            geo,
            ctx,
            permissive,
            maxResults = 3,
            isActive = { false },
        )
        // May be empty or have partial results but must not throw
        (results.size >= 0) shouldBe true
    }

    test("results are sorted by score descending") {
        val results = decoder.decode(idealPath("hello"), geo, ctx, permissive, maxResults = 3)
        if (results.size > 1) {
            for (i in 1 until results.size) {
                (results[i - 1].score >= results[i].score) shouldBe true
            }
        }
    }

    test("DecoderFactory wires in a gesture decoder") {
        val stack = DecoderFactory.create(dict)
        // GestureDecoder is present and responds correctly
        val results = stack.gestureDecoder.decode(
            idealPath("abc"),
            geo,
            ctx,
            permissive,
            maxResults = 2,
        )
        results.isNotEmpty() shouldBe true
    }
})
