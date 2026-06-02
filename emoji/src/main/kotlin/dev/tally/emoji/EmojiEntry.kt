package dev.tally.emoji

/**
 * A single emoji with its metadata.
 *
 * @param emoji     The emoji string as it should be rendered and inserted (e.g. "😀").
 * @param name      Short English name, matching the CLDR cldr-common annotation.
 * @param category  The display category this emoji belongs to.
 * @param keywords  Space-separated search keywords (from CLDR annotations).
 * @param skinTones Alternative skin-tone modifier variants, in Fitzpatrick order (1F3FB–1F3FF).
 *                  Empty for emoji that do not support skin tones.
 */
data class EmojiEntry(
    val emoji: String,
    val name: String,
    val category: EmojiCategory,
    val keywords: String,
    val skinTones: List<String> = emptyList(),
)
