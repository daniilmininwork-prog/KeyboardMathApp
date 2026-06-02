package dev.tally.keyboard.engine

/**
 * Immutable description of a keyboard layout loaded from a data file.
 *
 * Width fractions in each row must sum to ≤ 1.0 (validated by the parser; the engine
 * trusts values that survive that gate). The engine converts fractions to pixel geometry
 * in [LayoutEngine], keeping this model free of any dimensional units.
 */
data class LayoutDefinition(
    /** Stable identifier, e.g. "en_US_QWERTY". Matches the asset filename stem. */
    val id: String,
    /** BCP-47 locale tag for the primary language of this layout. */
    val locale: String,
    /** Writing direction; controls mirroring of key order in RTL scripts. */
    val direction: Direction,
    /** Ordered rows from top to bottom, each containing an ordered list of keys. */
    val rows: List<Row>,
)

/**
 * Text direction for a layout, determining whether key order is mirrored.
 *
 * RTL layouts have their key order reversed at render time so the rightmost key in
 * the data file appears on the right of the physical keyboard.
 */
enum class Direction { LTR, RTL }

/**
 * A single horizontal row of keys.
 *
 * The sum of [keys] width fractions must be ≤ 1.0 at parse time; any remainder
 * is distributed as implicit padding at the row edges.
 */
data class Row(val keys: List<KeyDef>)

/**
 * Definition of a single key.
 *
 * @param code      The primary Unicode code point (or a negative sentinel for special keys;
 *                  see [SpecialCode]).
 * @param label     Display label. Normally derived from [code] but can differ for specials.
 * @param moreKeys  Secondary characters reachable via long-press, ordered for display.
 * @param width     Fraction of the total row width occupied by this key (0.0, 1.0].
 * @param isSpecial True for functional keys (shift, backspace, enter, space, symbols).
 */
data class KeyDef(
    val code: Int,
    val label: String,
    val moreKeys: List<String> = emptyList(),
    val width: Float,
    val isSpecial: Boolean = false,
)

/**
 * Sentinel code values for keys that do not produce a character.
 *
 * Using negative values avoids collision with any valid Unicode code point.
 */
object SpecialCode {
    const val SHIFT            = -1
    const val DELETE           = -2
    const val SYMBOLS          = -3
    const val ENTER            = -4
    const val SPACE            = -5
    const val GLOBE            = -6
    /** Switch back to the alphabetic layer from numeric or symbols. */
    const val ALPHA            = -7
    /** Switch to the numeric layer from symbols (numeric-layer's "#+=" key returns here). */
    const val NUMERIC          = -8
    /** Toggle the optional number row at the top of the alphabetic layer. */
    const val NUMBER_ROW_TOGGLE = -9
    /** Activate on-device voice input (T5.2). */
    const val VOICE            = -10
}
