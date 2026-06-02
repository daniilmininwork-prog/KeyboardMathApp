package dev.tally.emoji

/**
 * The nine top-level Unicode emoji categories, in display order.
 *
 * Labels and ordering follow CLDR emoji annotation conventions.
 */
enum class EmojiCategory(val label: String) {
    RECENTS("Recent"),
    SMILEYS_EMOTION("Smileys & Emotion"),
    PEOPLE_BODY("People & Body"),
    ANIMALS_NATURE("Animals & Nature"),
    FOOD_DRINK("Food & Drink"),
    TRAVEL_PLACES("Travel & Places"),
    ACTIVITIES("Activities"),
    OBJECTS("Objects"),
    SYMBOLS("Symbols"),
}
