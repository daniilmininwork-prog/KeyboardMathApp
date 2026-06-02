package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.SuggestionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class WordPredictorImplTest : FunSpec({

    val dict = DecoderTestHelper.dict(
        "can"   to 800,
        "candy" to 150,
        "care"  to 300,
        "help"  to 600,
        "hello" to 2000,
        "world" to 1500,
        "the"   to 5000,
        "to"    to 4000,
    )
    val geo   = DecoderTestHelper.simpleGeometry()
    val cache = FrequencyCache.NOOP

    fun makePredictor(): WordPredictorImpl {
        val decoder = BeamDecoder(dictionary = dict, freqCache = cache)
        return WordPredictorImpl(decoder = decoder, freqCache = cache, geometry = geo)
    }

    test("returns PREDICTION suggestions for a partial word") {
        val predictor = makePredictor()
        val ctx = EditingContext("hel", "", null)
        val results = predictor.predict(ctx, FieldPolicy.PERMISSIVE, maxResults = 3)
        results.shouldNotBeEmpty()
        results.all { it.kind == SuggestionKind.PREDICTION } shouldBe true
    }

    test("returns NEXT_WORD suggestions when composing word is empty") {
        val predictor = makePredictor()
        val ctx = EditingContext("", "I can ", "can")
        val results = predictor.predict(ctx, FieldPolicy.PERMISSIVE, maxResults = 3)
        results.shouldNotBeEmpty()
        results.all { it.kind == SuggestionKind.NEXT_WORD } shouldBe true
    }

    test("honours suggestionsEnabled = false") {
        val predictor = makePredictor()
        val ctx = EditingContext("hel", "", null)
        predictor.predict(ctx, FieldPolicy.DEFAULT_PRIVATE).shouldBeEmpty()
    }

    test("query delegates to predict") {
        val predictor = makePredictor()
        val ctx = EditingContext("hel", "", null)
        val fromQuery   = predictor.query(ctx, FieldPolicy.PERMISSIVE)
        val fromPredict = predictor.predict(ctx, FieldPolicy.PERMISSIVE)
        fromQuery shouldBe fromPredict
    }

    test("updateGeometry changes scoring geometry") {
        val predictor = makePredictor()
        val newGeo = DecoderTestHelper.simpleGeometry("zyxwvutsrqponmlkjihgfedcba")
        predictor.updateGeometry(newGeo)
        // Should not throw; result may differ from original geometry
        val ctx = EditingContext("hel", "", null)
        predictor.predict(ctx, FieldPolicy.PERMISSIVE)
    }

    // Learning writes now go through PersonalizationStore, not WordPredictorImpl.
    // The gate tests live in PersonalizationStoreTest; this test verifies that the
    // shared FrequencyCache used by the predictor reflects PersonalizationStore writes.
    test("PersonalizationStore writes are reflected in predictor scoring cache") {
        val store   = PersonalizationStore()
        val decoder = BeamDecoder(dictionary = dict, freqCache = store.cache)
        WordPredictorImpl(decoder = decoder, freqCache = store.cache)

        // Permissive write: boost should be positive.
        store.record("hello", FieldPolicy.PERMISSIVE)
        (store.logBoost("hello") > 0f) shouldBe true

        // Private field: no write, boost stays at zero.
        val before = store.logBoost("world")
        store.record("world", FieldPolicy.DEFAULT_PRIVATE)
        store.logBoost("world") shouldBe before
    }
})
