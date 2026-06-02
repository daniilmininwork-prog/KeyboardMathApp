package dev.tally.prediction

import dev.tally.keyboard.engine.FieldPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeZero
import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeGreaterThan

/**
 * Verifies that [PersonalizationStore] enforces [FieldPolicy] gates before writing
 * to the backing [FrequencyCache].
 *
 * T2.5 acceptance: nothing learned or persisted from secure or no-learning fields.
 * These tests are the pure-JVM proof of that gate; instrumented surface coverage
 * is in [IncognitoEnforcementTest] in the `ime` module.
 */
class PersonalizationStoreTest : FunSpec({

    // ── gate: learningEnabled = false ─────────────────────────────────────────

    test("record is suppressed when learningEnabled is false") {
        val store = PersonalizationStore()
        val policy = FieldPolicy.DEFAULT_PRIVATE   // learningEnabled=false, persistAllowed=false
        store.record("hello", policy)
        store.logBoost("hello").shouldBeZero()
    }

    test("record is suppressed for password field policy") {
        val store = PersonalizationStore()
        val policy = FieldPolicy(
            learningEnabled    = false,
            suggestionsEnabled = false,
            glideEnabled       = false,
            previewMasked      = true,
            persistAllowed     = false,
            mathEnabled        = false,
        )
        store.record("password123", policy)
        store.snapshot().isEmpty() shouldBe true
    }

    test("record is suppressed when persistAllowed is false even if learningEnabled is true") {
        // Hypothetical mixed state: enforce that both flags must be true.
        val store = PersonalizationStore()
        val policy = FieldPolicy(
            learningEnabled    = true,
            suggestionsEnabled = true,
            glideEnabled       = true,
            previewMasked      = false,
            persistAllowed     = false,   // persist blocked
            mathEnabled        = true,
        )
        store.record("hello", policy)
        store.logBoost("hello").shouldBeZero()
        store.snapshot().isEmpty() shouldBe true
    }

    test("record is suppressed when learningEnabled is false even if persistAllowed is true") {
        val store = PersonalizationStore()
        val policy = FieldPolicy(
            learningEnabled    = false,   // learning blocked
            suggestionsEnabled = true,
            glideEnabled       = true,
            previewMasked      = false,
            persistAllowed     = true,
            mathEnabled        = true,
        )
        store.record("hello", policy)
        store.logBoost("hello").shouldBeZero()
        store.snapshot().isEmpty() shouldBe true
    }

    // ── gate: IME_FLAG_NO_PERSONALIZED_LEARNING equivalent ───────────────────

    test("incognito policy (learning off, persist off) produces zero boost after records") {
        val store = PersonalizationStore()
        // Incognito: learningEnabled=false, persistAllowed=false, suggestions/math still on
        val incognito = FieldPolicy(
            learningEnabled    = false,
            suggestionsEnabled = true,
            glideEnabled       = true,
            previewMasked      = false,
            persistAllowed     = false,
            mathEnabled        = true,
        )
        repeat(10) { store.record("secret", incognito) }
        store.logBoost("secret").shouldBeZero()
        store.snapshot().isEmpty() shouldBe true
    }

    // ── gate allows writes for permissive policy ──────────────────────────────

    test("record succeeds for PERMISSIVE policy") {
        val store = PersonalizationStore()
        store.record("hello", FieldPolicy.PERMISSIVE)
        store.logBoost("hello") shouldBeGreaterThan 0f
    }

    test("multiple records accumulate under PERMISSIVE policy") {
        val store = PersonalizationStore()
        store.record("hello", FieldPolicy.PERMISSIVE)
        val boost1 = store.logBoost("hello")
        store.record("hello", FieldPolicy.PERMISSIVE)
        store.logBoost("hello") shouldBeGreaterThan boost1
    }

    // ── no leakage across field transitions ──────────────────────────────────

    test("words recorded in a permissive field do not receive new boosts in a secure field") {
        val store = PersonalizationStore()
        store.record("hello", FieldPolicy.PERMISSIVE)
        val boostAfterPermissive = store.logBoost("hello")

        // Switch to a password field — record must not add new weight.
        val password = FieldPolicy.DEFAULT_PRIVATE
        store.record("hello", password)
        store.logBoost("hello") shouldBe boostAfterPermissive
    }

    test("no learning during no-learning session does not affect snapshot") {
        val store = PersonalizationStore()
        val noLearn = FieldPolicy(
            learningEnabled    = false,
            suggestionsEnabled = false,
            glideEnabled       = false,
            previewMasked      = true,
            persistAllowed     = false,
            mathEnabled        = false,
        )
        store.record("typed", noLearn)
        store.record("words", noLearn)
        store.record("in",    noLearn)
        store.record("secure", noLearn)
        store.snapshot().isEmpty() shouldBe true
    }

    // ── snapshot and loadFrom round-trip ─────────────────────────────────────

    test("snapshot reflects only permissive-field observations") {
        val store = PersonalizationStore()
        store.record("allowed", FieldPolicy.PERMISSIVE)
        store.record("blocked", FieldPolicy.DEFAULT_PRIVATE)
        val snap = store.snapshot()
        snap.containsKey("allowed") shouldBe true
        snap.containsKey("blocked") shouldBe false
    }

    test("loadFrom restores state and boosts are correct") {
        val store = PersonalizationStore()
        store.loadFrom(mapOf("the" to 50, "hello" to 20))
        store.logBoost("the") shouldBeGreaterThan store.logBoost("hello")
    }

    // ── NOOP variant ─────────────────────────────────────────────────────────

    test("NOOP store always returns zero boost regardless of policy") {
        val noop = PersonalizationStore.NOOP
        noop.record("hello", FieldPolicy.PERMISSIVE)
        noop.logBoost("hello").shouldBeZero()
    }

    test("NOOP snapshot is always empty") {
        PersonalizationStore.NOOP.snapshot().isEmpty() shouldBe true
    }
})
