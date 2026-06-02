@file:JvmName("DictCompiler")

package dev.tally.tools

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log2

/**
 * Compiles a raw SCOWL/ESDB word-frequency file into the Tally binary dictionary
 * format (.tally-dict) consumed by DictionaryLoader at runtime.
 *
 * Input format (one entry per line):
 *
 *   <word><TAB><frequency>
 *
 * Lines starting with '#' are comments.  Words longer than 255 bytes (UTF-8) are
 * silently skipped — the format uses a single-byte length prefix.
 *
 * Usage:
 *   ./gradlew :tools:dict-compiler:run --args="input.tsv output.tally-dict [maxWords]"
 *
 * The compiled asset is checked into prediction/src/main/assets/prediction/en_us.tally-dict.
 * Re-run this tool whenever the upstream SCOWL release changes and commit both
 * the updated tool invocation log and the new binary.
 *
 * Source data:
 *   SCOWL — https://github.com/kevina/scowl  (SCOWL-and-ESDB license, see below)
 *
 * License note: SCOWL uses a custom permissive license (BSD-like, no copyleft).
 * The compiled binary asset does not embed the license text verbatim; attribution
 * is carried in docs/data-ledger.toml and app/src/main/res/raw/notices.txt.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: DictCompiler <input.tsv> <output.tally-dict> [maxWords]"
    }

    val inputFile  = File(args[0])
    val outputFile = File(args[1])
    val maxWords   = if (args.size >= 3) args[2].toInt() else Int.MAX_VALUE

    require(inputFile.exists()) { "Input file not found: ${inputFile.absolutePath}" }

    // ── Parse input ───────────────────────────────────────────────────────────
    val entries = mutableListOf<Pair<String, Int>>() // word, frequency

    inputFile.forEachLine { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEachLine
        val tab = line.indexOf('\t')
        if (tab < 0) return@forEachLine
        val word  = line.substring(0, tab).trim().lowercase()
        val freq  = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
        if (word.isEmpty() || word.toByteArray(Charsets.UTF_8).size > 255) return@forEachLine
        if (freq <= 0) return@forEachLine
        entries += word to freq
    }

    // Sort descending by frequency, then alphabetically; take the top maxWords.
    val sorted = entries
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(maxWords)

    // Re-sort alphabetically for binary search in the loader.
    val words = sorted.sortedBy { it.first }

    // Compute approximate bigrams from consecutive word pairs (placeholder: full
    // bigram extraction requires a separate corpus pass; ship unigrams-only for M2
    // and extend to true bigrams when the corpus pipeline is set up).
    val bigrams: List<Triple<Int, Int, Float>> = emptyList()

    // ── Write binary ──────────────────────────────────────────────────────────
    outputFile.parentFile?.mkdirs()
    DataOutputStream(FileOutputStream(outputFile).buffered()).use { out ->
        // Magic + version
        out.write("TALLY_DICT".toByteArray(Charsets.US_ASCII))
        out.writeShort(1) // VERSION

        // Counts
        out.writeInt(words.size)
        out.writeInt(bigrams.size)

        // Words section
        val totalCount = words.sumOf { it.second.toLong().coerceAtLeast(1L) }
        for ((word, freq) in words) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            out.writeByte(bytes.size)
            out.write(bytes)
            out.writeInt(freq)
        }

        // Bigrams section (empty for now; expanded in T2.2 corpus integration)
        for ((w1, w2, lp) in bigrams) {
            writeInt24(out, w1)
            writeInt24(out, w2)
            out.writeFloat(lp)
        }
    }

    // Success: the output file itself is evidence of completion.
    // Gradle shows the task outcome in its own log.
    check(outputFile.exists()) { "Output file was not created: ${outputFile.absolutePath}" }
}

private fun writeInt24(out: DataOutputStream, value: Int) {
    out.writeByte((value shr 16) and 0xFF)
    out.writeByte((value shr 8) and 0xFF)
    out.writeByte(value and 0xFF)
}
