package dev.tally.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.tally.keyboard.engine.FieldPolicy
import dev.tally.keyboard.engine.Suggestion
import dev.tally.keyboard.engine.SuggestionKind
import dev.tally.prediction.DecoderFactory
import dev.tally.prediction.FrequencyCache
import dev.tally.prediction.PersonalizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end enforcement: verifies that the [PersonalizationStore] receives no writes
 * while a sensitive field is active, and that the [FieldPolicyFactory] correctly
 * identifies every sensitive-field variant.
 *
 * T2.5 acceptance criterion: "nothing learned/persisted from secure or no-learning fields."
 *
 * Test structure:
 *   1. [FieldPolicyFactory] derivation → policy flags are correct.
 *   2. [PersonalizationStore.record] gate → zero cache entries for all sensitive policies.
 *   3. Integration through [DecoderFactory] stack → store hooked to the same cache.
 *   4. Transition test → switching from permissive → sensitive → permissive only accumulates
 *      observations from the permissive windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncognitoEnforcementTest {

    // ── 1. Policy derivation sanity (ensure factory agrees with store gate) ──

    @Test
    fun `text password field derives learningEnabled=false`() {
        val policy = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse("password must disable learning", policy.learningEnabled)
        assertFalse("password must disable persist",  policy.persistAllowed)
    }

    @Test
    fun `visible password field derives learningEnabled=false`() {
        val policy = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertFalse(policy.learningEnabled)
        assertFalse(policy.persistAllowed)
    }

    @Test
    fun `web password field derives learningEnabled=false`() {
        val policy = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        assertFalse(policy.learningEnabled)
        assertFalse(policy.persistAllowed)
    }

    @Test
    fun `numeric PIN field derives learningEnabled=false`() {
        val policy = policyFor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertFalse(policy.learningEnabled)
        assertFalse(policy.persistAllowed)
    }

    @Test
    fun `NO_SUGGESTIONS field derives learningEnabled=false`() {
        val policy = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertFalse(policy.learningEnabled)
        assertFalse(policy.persistAllowed)
    }

    @Test
    fun `NO_PERSONALIZED_LEARNING flag derives learningEnabled=false persistAllowed=false`() {
        val info = EditorInfo().apply {
            inputType  = InputType.TYPE_CLASS_TEXT
            imeOptions = 0x1000000   // IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val policy = FieldPolicyFactory.from(info)
        assertFalse("incognito must disable learning", policy.learningEnabled)
        assertFalse("incognito must disable persist",  policy.persistAllowed)
        // But suggestions and math are still allowed in incognito.
        assertTrue("incognito allows suggestions", policy.suggestionsEnabled)
        assertTrue("incognito allows math",        policy.mathEnabled)
    }

    @Test
    fun `plain text field derives learningEnabled=true`() {
        val policy = policyFor(InputType.TYPE_CLASS_TEXT)
        assertTrue(policy.learningEnabled)
        assertTrue(policy.persistAllowed)
    }

    // ── 2. PersonalizationStore gate (zero writes in sensitive fields) ────────

    @Test
    fun `no cache entry recorded during text password field`() {
        assertZeroWritesFor(policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    @Test
    fun `no cache entry recorded during visible password field`() {
        assertZeroWritesFor(policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
    }

    @Test
    fun `no cache entry recorded during web password field`() {
        assertZeroWritesFor(policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun `no cache entry recorded during numeric PIN field`() {
        assertZeroWritesFor(policyFor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `no cache entry recorded during NO_SUGGESTIONS field`() {
        assertZeroWritesFor(policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun `no cache entry recorded during incognito field`() {
        val info = EditorInfo().apply {
            inputType  = InputType.TYPE_CLASS_TEXT
            imeOptions = 0x1000000
        }
        assertZeroWritesFor(FieldPolicyFactory.from(info))
    }

    @Test
    fun `cache entries are recorded during plain text field`() {
        val store = PersonalizationStore()
        store.record("hello", FieldPolicy.PERMISSIVE)
        assertTrue("permissive field must record the word", store.snapshot().isNotEmpty())
    }

    // ── 3. Integration through DecoderStack ──────────────────────────────────

    @Test
    fun `DecoderStack personalizationStore holds the same cache as the decoder`() {
        // The store exposed by DecoderFactory must be wired to the FrequencyCache that
        // the BeamDecoder reads, so any permitted write is reflected in scoring.
        val dict  = buildMiniDict()
        val store = PersonalizationStore()
        val stack = DecoderFactory.create(dictionary = dict, store = store)

        // The field is permissive: a record must be reflected in the boost.
        stack.personalizationStore.record("hello", FieldPolicy.PERMISSIVE)
        val boost = stack.personalizationStore.logBoost("hello")
        assertTrue("boost must be positive after record", boost > 0f)
    }

    @Test
    fun `DecoderStack personalizationStore ignores writes during secure field`() {
        val dict  = buildMiniDict()
        val store = PersonalizationStore()
        val stack = DecoderFactory.create(dictionary = dict, store = store)

        val pwPolicy = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        stack.personalizationStore.record("secret", pwPolicy)

        assertTrue("snapshot must be empty after secure-field write",
            stack.personalizationStore.snapshot().isEmpty())
    }

    // ── 4. Field-transition: learn/don't-learn across switches ───────────────

    @Test
    fun `only permissive-window words appear in snapshot after field transitions`() {
        val store = PersonalizationStore()

        val permissive = FieldPolicy.PERMISSIVE
        val secure     = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val incognito  = run {
            val info = EditorInfo().apply {
                inputType  = InputType.TYPE_CLASS_TEXT
                imeOptions = 0x1000000
            }
            FieldPolicyFactory.from(info)
        }

        // Permissive window: these words should appear in the snapshot.
        store.record("apple",  permissive)
        store.record("banana", permissive)

        // Secure window: these must NOT appear.
        store.record("hunter2",   secure)
        store.record("p@ssw0rd",  secure)

        // Incognito window: these must NOT appear.
        store.record("private",   incognito)
        store.record("browsing",  incognito)

        // Back to permissive: this word should appear.
        store.record("cherry",  permissive)

        val snap = store.snapshot()
        assertTrue ("apple in snap",   snap.containsKey("apple"))
        assertTrue ("banana in snap",  snap.containsKey("banana"))
        assertTrue ("cherry in snap",  snap.containsKey("cherry"))
        assertFalse("hunter2 not in snap",  snap.containsKey("hunter2"))
        assertFalse("p@ssw0rd not in snap", snap.containsKey("p@ssw0rd"))
        assertFalse("private not in snap",  snap.containsKey("private"))
        assertFalse("browsing not in snap", snap.containsKey("browsing"))
    }

    @Test
    fun `snapshot count matches exactly the number of permissive commits`() {
        val store = PersonalizationStore()
        val secure = policyFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        // 3 permissive distinct words, 5 secure words that must be ignored.
        store.record("one",   FieldPolicy.PERMISSIVE)
        store.record("two",   FieldPolicy.PERMISSIVE)
        store.record("three", FieldPolicy.PERMISSIVE)
        store.record("four",  secure)
        store.record("five",  secure)
        store.record("six",   secure)
        store.record("seven", secure)
        store.record("eight", secure)

        assertEquals("exactly 3 words learned", 3, store.snapshot().size)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun policyFor(inputType: Int): FieldPolicy =
        FieldPolicyFactory.from(EditorInfo().apply { this.inputType = inputType })

    private fun assertZeroWritesFor(policy: FieldPolicy) {
        val store = PersonalizationStore()
        // Simulate committing several words during a sensitive field session.
        listOf("hello", "world", "secret", "pin", "pass").forEach { word ->
            store.record(word, policy)
        }
        assertTrue(
            "Snapshot must be empty for policy learningEnabled=${policy.learningEnabled} " +
                "persistAllowed=${policy.persistAllowed}",
            store.snapshot().isEmpty(),
        )
    }

    private fun buildMiniDict(): dev.tally.prediction.EnglishDictionary {
        val entries = listOf("hello" to 2000, "world" to 1500, "the" to 5000)
        val sorted  = entries.sortedBy { it.first }
        val words   = sorted.map { it.first }.toTypedArray()
        val freqs   = sorted.map { it.second }.toIntArray()
        val total   = freqs.sumOf { it.toLong().coerceAtLeast(1L) }.toFloat()
        val logTotal = kotlin.math.log2(total)
        val logProbs = FloatArray(words.size) { i ->
            val adj = (freqs[i].toFloat() - 0.75f).coerceAtLeast(0.5f)
            kotlin.math.log2(adj) - logTotal
        }
        return dev.tally.prediction.EnglishDictionary(
            words           = words,
            unigramLogProbs = logProbs,
            bigramW1        = intArrayOf(),
            bigramW2        = intArrayOf(),
            bigramLogProbs  = floatArrayOf(),
        )
    }
}
