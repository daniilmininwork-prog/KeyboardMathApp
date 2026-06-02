package dev.tally.prediction

import dev.tally.keyboard.engine.GlidePoint
import dev.tally.keyboard.engine.KeyGeometry

/**
 * Builds the ideal swipe path for a candidate word given a keyboard geometry.
 *
 * The ideal path visits each key's center in the order the word spells, skipping
 * consecutive duplicate letters (double letters produce one visit, not two — the
 * finger does not need to return to the same key). This is the standard treatment
 * in SHARK² and FlorisBoard's gesture classifier.
 *
 * The resulting list is passed through [PathResampler] before scoring so that
 * the candidate path and the user's trace have the same number of sample points.
 */
internal class IdealPathBuilder {

    /**
     * Returns the ideal key-center path for [word] in [geometry].
     *
     * Keys are matched case-insensitively against the [KeyDef.label]'s first
     * character (the same convention used in [SpatialScorer]).
     *
     * Returns an empty list if fewer than 2 distinct key centers are found —
     * one-character words cannot form a meaningful swipe.
     */
    fun buildForWord(word: String, geometry: KeyGeometry): List<GlidePoint> {
        if (word.isEmpty()) return emptyList()

        val path = mutableListOf<GlidePoint>()
        var lastKey: String? = null

        for (ch in word.lowercase()) {
            val key = geometry.keys.firstOrNull { k ->
                k.keyDef.label.firstOrNull()?.lowercaseChar() == ch
            } ?: continue  // letter not on this layout — skip silently

            val label = key.keyDef.label
            if (label == lastKey) continue  // deduplicate consecutive same-key visits
            lastKey = label

            path += GlidePoint(key.centerX, key.centerY)
        }

        return path
    }
}
