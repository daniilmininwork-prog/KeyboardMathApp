package dev.tally.keyboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThan

class SuggestionTest : FunSpec({

    test("Suggestion fields are accessible") {
        val s = Suggestion(SuggestionKind.AUTOCORRECT, "hello", -1.5f)
        s.kind  shouldBe SuggestionKind.AUTOCORRECT
        s.text  shouldBe "hello"
        s.score shouldBe -1.5f
    }

    test("SuggestionKind covers all expected values") {
        val names = SuggestionKind.entries.map { it.name }.toSet()
        setOf("MATH", "AUTOCORRECT", "PREDICTION", "NEXT_WORD", "EMOJI", "CLIPBOARD", "INLINE_AUTOFILL")
            .forEach { name -> names.contains(name) shouldBe true }
    }

    test("Suggestion ranking by score") {
        val list = listOf(
            Suggestion(SuggestionKind.PREDICTION, "world",  -5f),
            Suggestion(SuggestionKind.PREDICTION, "hello", -2f),
            Suggestion(SuggestionKind.PREDICTION, "word",  -3f),
        ).sortedByDescending { it.score }

        list[0].text shouldBe "hello"
        list[1].text shouldBe "word"
        list[2].text shouldBe "world"
    }
})
