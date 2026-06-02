package dev.tally.prediction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class IdealPathBuilderTest : FunSpec({

    val geo     = DecoderTestHelper.simpleGeometry()  // a-z laid out left to right
    val builder = IdealPathBuilder()

    test("empty word returns empty path") {
        builder.buildForWord("", geo) shouldBe emptyList()
    }

    test("single-char word returns one point") {
        val path = builder.buildForWord("a", geo)
        path shouldHaveSize 1
    }

    test("double-letter word deduplicates consecutive same key") {
        // "aa" should visit key 'a' only once
        builder.buildForWord("aa", geo) shouldHaveSize 1
    }

    test("path for 'abc' visits three distinct key centers left-to-right") {
        val path = builder.buildForWord("abc", geo)
        path shouldHaveSize 3
        // In simpleGeometry, 'a' is at x=25, 'b' at x=75, 'c' at x=125
        (path[0].x < path[1].x) shouldBe true
        (path[1].x < path[2].x) shouldBe true
    }

    test("character not in geometry is skipped without error") {
        // simpleGeometry has a-z; '1' is not present
        val path = builder.buildForWord("a1b", geo)
        path shouldHaveSize 2  // only 'a' and 'b' resolved
    }

    test("word with alternating double letters deduplicates correctly") {
        // "abba" → a, b, a (three visits, not four)
        val path = builder.buildForWord("abba", geo)
        path shouldHaveSize 3
    }
})
