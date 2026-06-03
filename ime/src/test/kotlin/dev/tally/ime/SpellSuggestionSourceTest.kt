package dev.tally.ime

import dev.tally.keyboard.engine.EditingContext
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.prediction.DecoderFactory
import dev.tally.prediction.EnglishDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

/**
 * Unit tests for [SpellSuggestionSource] — the tap-to-correct path that puts dictionary
 * corrections for a red-underlined (misspelled) composing word into the strip.
 *
 * Uses a small in-memory dictionary built here so the test is self-contained and does not depend
 * on the shipped asset being on the JVM test classpath. The lookup logic is identical to production
 * ([DecoderFactory.create] wires the same SpellChecker over whatever dictionary it is given).
 */
class SpellSuggestionSourceTest {

    private val policy = FieldPolicy.PERMISSIVE

    /** Builds a minimal sorted [EnglishDictionary] (mirrors the prediction module's test helper). */
    private fun dict(vararg entries: Pair<String, Int>): EnglishDictionary {
        val sorted = entries.sortedBy { it.first }
        val words = sorted.map { it.first }.toTypedArray()
        val freqs = sorted.map { it.second }.toIntArray()
        val total = freqs.map { it.toLong().coerceAtLeast(1L) }.sum()
        val logTotal = log2(total.toFloat())
        val logProbs = FloatArray(words.size) { i ->
            val adj = (freqs[i].toFloat() - 0.75f).coerceAtLeast(0.5f)
            log2(adj) - logTotal
        }
        return EnglishDictionary(
            words = words,
            unigramLogProbs = logProbs,
            bigramW1 = intArrayOf(),
            bigramW2 = intArrayOf(),
            bigramLogProbs = floatArrayOf(),
        )
    }

    private fun spellChecker() =
        DecoderFactory.create(
            dict("hello" to 2000, "help" to 600, "world" to 1500, "the" to 9000),
        ).spellChecker

    @Test
    fun misspelledWord_offersCorrections_asAutocorrectKind() {
        val source = SpellSuggestionSource(spellChecker(), enabled = { true })

        val result = source.query(EditingContext("helo", "helo", null), policy)

        assertTrue("Corrections must be offered for a misspelled word", result.isNotEmpty())
        assertTrue(
            "All spell corrections surface as AUTOCORRECT so the best fix ranks first",
            result.all { it.kind == SuggestionKind.AUTOCORRECT },
        )
        // The intended word should be among the offered corrections.
        assertTrue(result.any { it.text.equals("hello", ignoreCase = true) })
    }

    @Test
    fun knownWord_offersNoSpellCorrections() {
        val source = SpellSuggestionSource(spellChecker(), enabled = { true })

        val result = source.query(EditingContext("hello", "hello", null), policy)

        assertTrue("A known word must not produce spell corrections", result.isEmpty())
    }

    @Test
    fun disabled_offersNothing() {
        val source = SpellSuggestionSource(spellChecker(), enabled = { false })

        val result = source.query(EditingContext("helo", "helo", null), policy)

        assertTrue(result.isEmpty())
    }

    @Test
    fun spellCorrectionNeverCarriesConfidence_soSpaceAutocorrectDoesNotFireOnIt() {
        // Only AutocorrectorImpl produces a calibrated confidence; a mere spell offer must not, or
        // the controller's Space-autocorrect gate could silently apply it.
        val source = SpellSuggestionSource(spellChecker(), enabled = { true })

        val result = source.query(EditingContext("helo", "helo", null), policy)

        assertFalse(result.isEmpty())
        assertTrue(result.all { it.confidence == null })
    }

    @Test
    fun secureField_offersNothing() {
        val source = SpellSuggestionSource(spellChecker(), enabled = { true })

        val result = source.query(EditingContext("helo", "helo", null), FieldPolicy.DEFAULT_PRIVATE)

        assertEquals(emptyList<Any>(), result)
    }
}
