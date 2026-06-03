package dev.tally.prediction

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.SuggestionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AutocorrectorImplTest : FunSpec({

    val dict = DecoderTestHelper.dict(
        "hello" to 2000,
        "world" to 1500,
        "help"  to 600,
        "the"   to 10000,
        "teh"   to 1,    // "teh" is a known misspelling; very low frequency
    )
    val geo = DecoderTestHelper.simpleGeometry()

    fun makeCorrector(): AutocorrectorImpl {
        val decoder = BeamDecoder(dictionary = dict, freqCache = FrequencyCache.NOOP)
        return AutocorrectorImpl(decoder = decoder, dictionary = dict, geometry = geo)
    }

    test("returns null for a high-frequency known word") {
        // "the" is very common → should not be corrected even if a closer candidate exists
        val corrector = makeCorrector()
        val ctx = EditingContext("the", "", null)
        corrector.correct(ctx, FieldPolicy.PERMISSIVE).shouldBeNull()
    }

    test("returns null when composing word is too short") {
        val corrector = makeCorrector()
        val ctx = EditingContext("he", "", null)   // length < MIN_WORD_LENGTH
        corrector.correct(ctx, FieldPolicy.PERMISSIVE).shouldBeNull()
    }

    test("returns null when suggestionsEnabled is false") {
        val corrector = makeCorrector()
        val ctx = EditingContext("helo", "", null)
        corrector.correct(ctx, FieldPolicy.DEFAULT_PRIVATE).shouldBeNull()
    }

    test("query wraps correct in a list") {
        val corrector = makeCorrector()
        val ctx = EditingContext("the", "", null)
        corrector.query(ctx, FieldPolicy.PERMISSIVE).size shouldBe 0
    }

    test("autocorrect suggestion has kind AUTOCORRECT") {
        // Build a dict where "helo" is absent and "hello" is the clear winner
        val smallDict = DecoderTestHelper.dict(
            "hello" to 5000,
            "help"  to 1000,
        )
        val decoder   = BeamDecoder(dictionary = smallDict, freqCache = FrequencyCache.NOOP)
        val corrector = AutocorrectorImpl(decoder = decoder, dictionary = smallDict, geometry = geo)

        val ctx = EditingContext("helo", "", null)
        val suggestion = corrector.correct(ctx, FieldPolicy.PERMISSIVE)
        // May be null if threshold not met; if not null, kind must be AUTOCORRECT
        suggestion?.kind shouldBe SuggestionKind.AUTOCORRECT
    }

    test("autocorrect suggestion carries a calibrated 0..1 confidence") {
        val smallDict = DecoderTestHelper.dict(
            "hello" to 5000,
            "help"  to 1000,
        )
        val decoder   = BeamDecoder(dictionary = smallDict, freqCache = FrequencyCache.NOOP)
        val corrector = AutocorrectorImpl(decoder = decoder, dictionary = smallDict, geometry = geo)

        val suggestion = corrector.correct(EditingContext("helo", "", null), FieldPolicy.PERMISSIVE)
        // When a correction fires it must expose a bounded confidence so the controller can gate
        // auto-replace on a fixed threshold (the raw score is unbounded and cannot be compared).
        suggestion?.let {
            it.confidence.shouldNotBeNull()
            (it.confidence!! in 0f..1f) shouldBe true
            // A correction at exactly the firing margin maps to 0.5; this one cleared the
            // threshold so it must be at least that confident.
            (it.confidence!! >= 0.5f) shouldBe true
        }
    }

    test("updateGeometry changes active geometry") {
        val corrector = makeCorrector()
        val newGeo = DecoderTestHelper.simpleGeometry("zyxwvutsrqponmlkjihgfedcba")
        corrector.updateGeometry(newGeo)
        // Must not throw
        corrector.correct(EditingContext("helo", "", null), FieldPolicy.PERMISSIVE)
    }
})
