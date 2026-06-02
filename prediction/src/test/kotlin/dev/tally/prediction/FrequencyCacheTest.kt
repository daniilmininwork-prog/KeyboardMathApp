package dev.tally.prediction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeZero
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class FrequencyCacheTest : FunSpec({

    test("fresh cache returns zero boost for unknown word") {
        val cache = FrequencyCache()
        cache.logBoost("hello").shouldBeZero()
    }

    test("recording a word increases its boost") {
        val cache = FrequencyCache()
        cache.record("hello")
        cache.logBoost("hello") shouldBeGreaterThan 0f
    }

    test("boost grows with additional records") {
        val cache = FrequencyCache()
        cache.record("hello")
        val boost1 = cache.logBoost("hello")
        cache.record("hello")
        val boost2 = cache.logBoost("hello")
        boost2 shouldBeGreaterThan boost1
    }

    test("case-insensitive recording and lookup") {
        val cache = FrequencyCache()
        cache.record("Hello")
        cache.logBoost("hello") shouldBeGreaterThan 0f
        cache.logBoost("HELLO") shouldBeGreaterThan 0f
    }

    test("boost is capped at MAX_BOOST") {
        val cache = FrequencyCache()
        repeat(1000) { cache.record("hello") }
        val boost = cache.logBoost("hello")
        (boost <= FrequencyCache.MAX_BOOST) shouldBe true
    }

    test("snapshot returns current counts") {
        val cache = FrequencyCache()
        cache.record("the")
        cache.record("the")
        cache.record("of")
        val snap = cache.snapshot()
        snap["the"] shouldBe 2
        snap["of"]  shouldBe 1
    }

    test("loadFrom restores counts") {
        val cache = FrequencyCache()
        cache.loadFrom(mapOf("hello" to 5, "world" to 3))
        cache.logBoost("hello") shouldBeGreaterThan cache.logBoost("world")
    }

    test("eviction respects maxEntries") {
        val cache = FrequencyCache(maxEntries = 3)
        cache.record("a")
        cache.record("b")
        cache.record("c")
        cache.record("d")  // triggers eviction
        cache.snapshot().size shouldBe 3
    }

    test("NOOP always returns zero boost") {
        FrequencyCache.NOOP.logBoost("hello").shouldBeZero()
        FrequencyCache.NOOP.record("hello")  // must not throw
        FrequencyCache.NOOP.logBoost("hello").shouldBeZero()
    }

    test("blank words are not recorded") {
        val cache = FrequencyCache()
        cache.record("   ")
        cache.snapshot().isEmpty() shouldBe true
    }
})
