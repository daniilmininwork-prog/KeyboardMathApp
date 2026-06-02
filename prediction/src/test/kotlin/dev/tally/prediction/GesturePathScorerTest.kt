package dev.tally.prediction

import dev.tally.keyboard.engine.GlidePoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class GesturePathScorerTest : FunSpec({

    val geo     = DecoderTestHelper.simpleGeometry()
    val scorer  = GesturePathScorer(sigma = 50f)
    val resampler = PathResampler(targetCount = 8)

    fun traceForWord(word: String): List<GlidePoint> {
        val ideal = IdealPathBuilder().buildForWord(word, geo)
        return resampler.resample(ideal)
    }

    test("perfect trace for a word scores higher than a trace for a distant word") {
        val tracePts = traceForWord("abc")
        val exactScore = scorer.score("abc", tracePts, geo, 50f)
        val farScore   = scorer.score("xyz", tracePts, geo, 50f)
        exactScore shouldBeGreaterThan farScore
    }

    test("trace too short returns NEGATIVE_INFINITY") {
        val single = listOf(GlidePoint(25f, 30f))
        scorer.score("abc", single, geo, 50f) shouldBe Float.NEGATIVE_INFINITY
    }

    test("single-char word ideal path returns NEGATIVE_INFINITY") {
        val tracePts = traceForWord("abc")
        scorer.score("a", tracePts, geo, 50f) shouldBe Float.NEGATIVE_INFINITY
    }

    test("finite score returned for a real word with matching trace") {
        val tracePts = traceForWord("hello")
        val score = scorer.score("hello", tracePts, geo, 50f)
        (score.isFinite()) shouldBe true
    }
})
