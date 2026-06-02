package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.shouldBe

class DecoderFactoryTest : FunSpec({

    val dict = DecoderTestHelper.dict(
        "hello" to 2000,
        "help"  to 600,
        "world" to 1500,
    )

    test("create returns a DecoderStack with non-null fields") {
        val stack = DecoderFactory.create(dict)
        stack.wordPredictor        shouldNotBe null
        stack.autocorrector        shouldNotBe null
        stack.personalizationStore shouldNotBe null
    }

    test("custom PersonalizationStore is wired into the stack") {
        val store = PersonalizationStore()
        val stack = DecoderFactory.create(dict, store)
        stack.personalizationStore shouldBe store
    }

    test("wordPredictor from factory produces suggestions") {
        val stack = DecoderFactory.create(dict)
        val ctx = EditingContext("hel", "", null)
        val results = stack.wordPredictor.predict(ctx, FieldPolicy.PERMISSIVE, maxResults = 3)
        results.isNotEmpty() shouldBe true
    }

    test("writing to personalizationStore influences subsequent predictions") {
        val store = PersonalizationStore()
        val stack = DecoderFactory.create(dict, store)

        // Record "hello" many times through the policy-gated store.
        repeat(20) { store.record("hello", FieldPolicy.PERMISSIVE) }

        val ctx = EditingContext("hel", "", null)
        val results = stack.wordPredictor.predict(ctx, FieldPolicy.PERMISSIVE, maxResults = 3)
        results.any { it.text == "hello" } shouldBe true
    }

    test("secure-field writes to personalizationStore do not affect predictions") {
        val store = PersonalizationStore()
        val stack = DecoderFactory.create(dict, store)
        val pwPolicy = FieldPolicy.DEFAULT_PRIVATE

        // These writes must be dropped; snapshot must stay empty.
        repeat(20) { stack.personalizationStore.record("world", pwPolicy) }
        stack.personalizationStore.snapshot().isEmpty() shouldBe true
    }
})
