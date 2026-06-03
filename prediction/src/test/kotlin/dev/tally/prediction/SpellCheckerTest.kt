package dev.tally.prediction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SpellCheckerTest : FunSpec({

    val dict = DecoderTestHelper.dict(
        "hello" to 2000,
        "help"  to 600,
        "world" to 1500,
        "the"   to 9000,
        "cat"   to 800,
    )
    // The factory wires the SpellChecker against the same decoder used everywhere else.
    val spell = DecoderFactory.create(dict).spellChecker

    test("known word is not flagged") {
        spell.isMisspelled("hello").shouldBeFalse()
        spell.isMisspelled("world").shouldBeFalse()
    }

    test("unknown word is flagged") {
        spell.isMisspelled("helo").shouldBeTrue()
        spell.isMisspelled("wrld").shouldBeTrue()
    }

    test("lookup is case-insensitive — capitalised known words are not flagged") {
        spell.isMisspelled("Hello").shouldBeFalse()
        spell.isMisspelled("WORLD").shouldBeFalse()
    }

    test("words shorter than the minimum check length are never flagged") {
        // "zz" is not in the dictionary but is below MIN_CHECK_LENGTH, so it is left alone.
        spell.isMisspelled("zz").shouldBeFalse()
        spell.isMisspelled("a").shouldBeFalse()
    }

    test("tokens containing non-letters are never flagged") {
        spell.isMisspelled("v2").shouldBeFalse()
        spell.isMisspelled("hello!").shouldBeFalse()
        spell.isMisspelled("3.14").shouldBeFalse()
    }

    test("corrections for a misspelling include the intended word") {
        // "helo" is one deletion away from "hello"; the shared beam decoder ranks it top.
        spell.corrections("helo").shouldContain("hello")
    }

    test("corrections never include the typed word itself") {
        spell.corrections("hello").none { it.equals("hello", ignoreCase = true) }.shouldBe(true)
    }

    test("corrections for empty input are empty") {
        spell.corrections("").shouldBe(emptyList())
    }
})
