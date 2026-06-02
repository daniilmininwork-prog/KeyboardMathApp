package dev.tally.ime

/**
 * Static layout definitions for each keyboard state.
 *
 * All rows are designed around a 10-unit wide grid so that a base unit = rowWidth / 10.
 * Row 2 (ASDFGHJKL, 9 keys) uses startOffsetUnits = 0.5 to centre it within the grid.
 *
 * Accent data (moreKeys) is static and permissive — the characters are Unicode code points
 * that belong to the Unicode Standard; no third-party data license is involved.
 */
internal object KeyboardLayout {

    private fun char(c: Char, vararg more: String) =
        Key(KeyCode.Char(c), c.toString(), moreKeys = more.toList())

    private fun chars(s: String) = s.map { char(it) }

    // Accent lists mirror the en_US_QWERTY layout asset so both paths stay consistent.
    // Only letters that actually have common accented forms in Latin-script languages are listed.
    private val LOWER_ROW_0 = listOf(
        char('q'),
        char('w'),
        char('e', "é", "è", "ê", "ë", "ě", "ē"),
        char('r'),
        char('t'),
        char('y'),
        char('u', "ú", "ù", "û", "ü", "ū"),
        char('i', "í", "ì", "î", "ï", "ī"),
        char('o', "ó", "ò", "ô", "ö", "ø", "ō"),
        char('p'),
    )

    private val LOWER_ROW_1 = listOf(
        char('a', "á", "à", "â", "ä", "æ", "ā"),
        char('s', "ś", "š", "ß"),
        char('d'),
        char('f'),
        char('g'),
        char('h'),
        char('j'),
        char('k'),
        char('l'),
    )

    private val LOWER_ROW_2 = listOf(
        Key(KeyCode.Shift, "⇧", widthUnits = 1.5f, isSpecial = true),
        char('z'),
        char('x'),
        char('c', "ç", "ć", "č"),
        char('v'),
        char('b'),
        char('n', "ñ", "ń", "ň"),
        char('m'),
        Key(KeyCode.Backspace, "⌫", widthUnits = 1.5f, isSpecial = true),
    )

    private val BOTTOM_ROW = listOf(
        Key(KeyCode.SwitchToNumeric, "123", widthUnits = 2f, isSpecial = true),
        Key(KeyCode.Char(','), ",", moreKeys = listOf("!", "?", ";", ":")),
        Key(KeyCode.Space, "", widthUnits = 4f),
        Key(KeyCode.Char('.'), ".", moreKeys = listOf(",", "!", "?", ";", ":", "…")),
        Key(KeyCode.Enter, "↵", widthUnits = 2f, isSpecial = true),
    )

    val ALPHA_LOWER: List<KeyRow> = listOf(
        // q w e r t y u i o p  (10 × 1.0 = 10 units)
        KeyRow(LOWER_ROW_0),
        // a s d f g h j k l  (9 × 1.0 = 9 units, centred with 0.5-unit inset each side)
        KeyRow(LOWER_ROW_1, startOffsetUnits = 0.5f),
        // ⇧  z x c v b n m  ⌫  (1.5 + 7 + 1.5 = 10 units)
        KeyRow(LOWER_ROW_2),
        // 123  ,  [space]  .  ↵  (2 + 1 + 4 + 1 + 2 = 10 units)
        KeyRow(BOTTOM_ROW),
    )

    val ALPHA_UPPER: List<KeyRow> = listOf(
        KeyRow(LOWER_ROW_0.map { k ->
            // Lift lower labels to upper; moreKeys stay as-is (accented capitals handled on commit).
            if (k.code is KeyCode.Char) {
                k.copy(code = KeyCode.Char(k.label[0].uppercaseChar()), label = k.label.uppercase())
            } else k
        }),
        KeyRow(LOWER_ROW_1.map { k ->
            if (k.code is KeyCode.Char) {
                k.copy(code = KeyCode.Char(k.label[0].uppercaseChar()), label = k.label.uppercase())
            } else k
        }, startOffsetUnits = 0.5f),
        KeyRow(LOWER_ROW_2.map { k ->
            if (k.code is KeyCode.Char) {
                k.copy(code = KeyCode.Char(k.label[0].uppercaseChar()), label = k.label.uppercase())
            } else k
        }),
        KeyRow(BOTTOM_ROW),
    )

    /**
     * Upper-case layout with the shift key showing the **locked** (caps-lock) indicator.
     *
     * Identical to [ALPHA_UPPER] in every key except the shift key, which carries the ⇪ label
     * (U+21EA UPWARDS WHITE ARROW FROM BAR) to distinguish sustained caps-lock from the
     * one-shot latched state shown by ⇧.
     *
     * This distinction is the "latched-vs-locked visuals" acceptance criterion for T1.7.
     * The rendering layer (T1.12) may later replace glyphs with tinted drawables; the distinct
     * label here ensures the difference is already represented in the data and a11y tree.
     */
    val ALPHA_LOCKED: List<KeyRow> = listOf(
        KeyRow(LOWER_ROW_0.map { k ->
            if (k.code is KeyCode.Char) {
                k.copy(code = KeyCode.Char(k.label[0].uppercaseChar()), label = k.label.uppercase())
            } else k
        }),
        KeyRow(LOWER_ROW_1.map { k ->
            if (k.code is KeyCode.Char) {
                k.copy(code = KeyCode.Char(k.label[0].uppercaseChar()), label = k.label.uppercase())
            } else k
        }, startOffsetUnits = 0.5f),
        KeyRow(LOWER_ROW_2.map { k ->
            when {
                k.code == KeyCode.Shift -> k.copy(label = "⇪")  // caps-lock indicator
                k.code is KeyCode.Char -> k.copy(
                    code  = KeyCode.Char(k.label[0].uppercaseChar()),
                    label = k.label.uppercase(),
                )
                else -> k
            }
        }),
        KeyRow(BOTTOM_ROW),
    )

    val NUMERIC: List<KeyRow> = listOf(
        // 1 2 3 4 5 6 7 8 9 0  (10 × 1.0 = 10 units)
        KeyRow(chars("1234567890")),
        // - / : ; ( ) $ & @ "  (10 × 1.0 = 10 units)
        KeyRow(listOf(
            char('-'), char('/'), char(':'), char(';'), char('('),
            char(')'), char('$'), char('&'), char('@'), char('"'),
        )),
        // #+=  . , ? ! '  ⌫  (1.5 + 5 + 1.5 + 2-unit gap via wider backspace = total 10 units)
        // actually: 1.5 + 1+1+1+1+1 + 1.5 = 8; pad to 10 by making #+= and ⌫ wider
        // Use: #+= (2.0) + . , ? ! ' (5×1.0) + ⌫ (2.0 + 1 padding) ... let's do:
        // #+= (2.0) + . , ? ! ' (5×1.0) + ⌫ (3.0) = 10 units
        KeyRow(listOf(
            Key(KeyCode.SwitchToSymbols, "#+=", widthUnits = 2f, isSpecial = true),
            char('.'), char(','), char('?'), char('!'), char('\''),
            Key(KeyCode.Backspace, "⌫", widthUnits = 3f, isSpecial = true),
        )),
        // ABC  [space]  ↵  (2 + 6 + 2 = 10 units)
        KeyRow(listOf(
            Key(KeyCode.SwitchToAlpha, "ABC", widthUnits = 2f, isSpecial = true),
            Key(KeyCode.Space, "", widthUnits = 6f),
            Key(KeyCode.Enter, "↵", widthUnits = 2f, isSpecial = true),
        )),
    )

    val SYMBOLS: List<KeyRow> = listOf(
        // [ ] { } # % ^ * + =  (10 × 1.0 = 10 units)
        KeyRow(listOf(
            char('['), char(']'), char('{'), char('}'), char('#'),
            char('%'), char('^'), char('*'), char('+'), char('='),
        )),
        // _ \ | ~ < > € £ ¥ •  (10 × 1.0 = 10 units)
        KeyRow(listOf(
            char('_'), char('\\'), char('|'), char('~'), char('<'),
            char('>'), char('€'), char('£'), char('¥'), char('•'),
        )),
        // 123 (2.0) + . , ? ! ' (5×1.0) + ⌫ (3.0) = 10 units
        KeyRow(listOf(
            Key(KeyCode.SwitchToNumeric, "123", widthUnits = 2f, isSpecial = true),
            char('.'), char(','), char('?'), char('!'), char('\''),
            Key(KeyCode.Backspace, "⌫", widthUnits = 3f, isSpecial = true),
        )),
        KeyRow(listOf(
            Key(KeyCode.SwitchToAlpha, "ABC", widthUnits = 2f, isSpecial = true),
            Key(KeyCode.Space, "", widthUnits = 6f),
            Key(KeyCode.Enter, "↵", widthUnits = 2f, isSpecial = true),
        )),
    )
}
