package dev.tally.prediction

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Verifies that the bundled English dictionary asset (en_us.tally-dict) is
 * present, parseable, and within the size budget (D17: ≤ 5 MB).
 *
 * The test locates the asset via the module's source tree; in CI this runs as
 * a JVM unit test before device packaging, catching format regressions early.
 */
class BundledAssetTest : FreeSpec({

    "bundled asset file exists" {
        assetFile().exists() shouldBe true
    }

    "bundled asset is within the 5 MB size budget" {
        val maxBytes = 5L * 1024 * 1024
        (assetFile().length() < maxBytes) shouldBe true
    }

    "bundled asset parses without error" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        dict shouldNotBe null
    }

    "bundled asset contains a non-trivial number of words" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        // The English lexicon should have at least a few hundred words to be useful
        dict.wordCount shouldBeGreaterThan 100
    }

    "bundled asset includes core English function words" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        // These words should be present in any credible English lexicon
        val coreWords = listOf("the", "and", "to", "of", "a", "in", "is", "it", "you", "that")
        val present = coreWords.count { dict.indexOf(it) >= 0 }
        // At least 80% of core function words must be in the lexicon
        present shouldBeGreaterThan (coreWords.size * 8 / 10)
    }

    "bundled asset words are sorted (enables binary search)" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        if (dict.wordCount >= 2) {
            var sorted = true
            for (i in 0 until dict.wordCount - 1) {
                if (dict.words[i] > dict.words[i + 1]) {
                    sorted = false
                    break
                }
            }
            sorted shouldBe true
        }
    }

    "all unigram log-probabilities are negative" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        dict.unigramLogProbs.all { it < 0f } shouldBe true
    }

    "high-frequency words have higher probability than rare words" {
        val dict = assetFile().inputStream().use { DictionaryLoader.parseFrom(it) }
        // "the" is the most common English word; "mountain" is less common
        val probThe      = dict.unigramLogProb("the")
        val probMountain = dict.unigramLogProb("mountain")
        // Both present → "the" must rank higher
        if (dict.indexOf("the") >= 0 && dict.indexOf("mountain") >= 0) {
            (probThe > probMountain) shouldBe true
        }
    }
})

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun assetFile(): File {
    // When running as a Gradle unit test, the working directory is the module root.
    // Asset is at src/main/assets/prediction/en_us.tally-dict relative to module.
    val candidates = listOf(
        File("src/main/assets/${DictionaryLoader.ASSET_PATH}"),
        // Fallback for IDE runs that set cwd to the repo root.
        File("prediction/src/main/assets/${DictionaryLoader.ASSET_PATH}"),
    )
    return candidates.first { it.exists() }
}
