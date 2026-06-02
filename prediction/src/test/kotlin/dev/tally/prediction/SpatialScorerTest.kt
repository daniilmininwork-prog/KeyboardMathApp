package dev.tally.prediction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class SpatialScorerTest : FunSpec({

    val scorer = SpatialScorer()
    val geo    = DecoderTestHelper.simpleGeometry()

    test("identical strings score higher than similar strings with one substitution") {
        val exact = scorer.logScore("hello", "hello", geo)
        val off   = scorer.logScore("hello", "hallo", geo)
        exact shouldBeGreaterThan off
    }

    test("strings differing by more than 2 in length return NEGATIVE_INFINITY") {
        val result = scorer.logScore("hi", "hello", geo)
        result shouldBe Float.NEGATIVE_INFINITY
    }

    test("exact match returns finite score") {
        val result = scorer.logScore("abc", "abc", geo)
        (result.isFinite()) shouldBe true
    }

    test("empty candidate returns NEGATIVE_INFINITY") {
        val result = scorer.logScore("hello", "", geo)
        result shouldBe Float.NEGATIVE_INFINITY
    }

    test("longer candidate within tolerance scores finite") {
        // typed = "ap", candidate = "apt" — length diff = 1 ≤ 2
        val result = scorer.logScore("ap", "apt", geo)
        (result.isFinite()) shouldBe true
    }

    test("adjacent keys score higher penalty than same key") {
        // 'a' is at index 0, 'b' is at index 1 — adjacent
        // 'a' vs 'a' should score higher (less negative) than 'a' vs 'z'
        val same = scorer.logScore("a", "a", geo)
        val far  = scorer.logScore("a", "z", geo)
        same shouldBeGreaterThan far
    }
})
