package dev.tally.keyboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class EditingContextTest : FunSpec({

    test("EMPTY has empty composing word and no word-before-cursor") {
        EditingContext.EMPTY.composingWord shouldBe ""
        EditingContext.EMPTY.textBeforeCursor shouldBe ""
        EditingContext.EMPTY.wordBeforeCursor.shouldBeNull()
    }

    test("data class equality and copy") {
        val ctx = EditingContext(
            composingWord    = "helo",
            textBeforeCursor = "I said helo",
            wordBeforeCursor = "said",
        )
        ctx.copy(composingWord = "hello") shouldBe EditingContext(
            composingWord    = "hello",
            textBeforeCursor = "I said helo",
            wordBeforeCursor = "said",
        )
    }

    test("SuggestionSource query honours policy gate") {
        val source = SuggestionSource { ctx, policy ->
            if (!policy.suggestionsEnabled) emptyList()
            else listOf(Suggestion(SuggestionKind.PREDICTION, ctx.composingWord, 1f))
        }

        val ctx = EditingContext(composingWord = "hi", textBeforeCursor = "", wordBeforeCursor = null)
        source.query(ctx, FieldPolicy.DEFAULT_PRIVATE) shouldBe emptyList()
        source.query(ctx, FieldPolicy.PERMISSIVE).size shouldBe 1
    }
})
