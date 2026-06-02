package dev.tally.layouts

import dev.tally.keyboard.engine.Direction
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyDescriptor
import dev.tally.keyboard.engine.LayoutDefinition
import dev.tally.keyboard.engine.Row
import dev.tally.keyboard.engine.SpecialCode

/**
 * Parses a layout JSON file into a [LayoutDefinition] and validates width fractions.
 *
 * The parser is intentionally minimal: it handles the subset of JSON needed by the
 * layout format and gives precise error messages for the validation rules that matter
 * most (width overflow, missing required fields). It does not depend on any JSON
 * library so the `layouts` module adds no transitive runtime dependency.
 *
 * Layout JSON schema (field names are normative):
 * ```
 * {
 *   "id":        "<string>",
 *   "locale":    "<BCP-47>",
 *   "direction": "LTR" | "RTL",
 *   "rows": [
 *     {
 *       "keys": [
 *         {
 *           "code":      <int>  | "<special>",
 *           "label":     "<string>",
 *           "moreKeys":  ["<string>", ...],   // optional
 *           "width":     <float>,             // fraction of row width
 *           "isSpecial": true | false         // optional, default false
 *         }, ...
 *       ]
 *     }, ...
 *   ]
 * }
 * ```
 *
 * Special key codes are expressed as strings: "SHIFT", "DELETE", "SYMBOLS",
 * "ENTER", "SPACE", "GLOBE". The parser maps these to [SpecialCode] sentinels.
 */
object LayoutParser {

    /** Maximum allowed deviation from 1.0 when validating row width sums. */
    private const val WIDTH_EPSILON = 1e-4f

    /**
     * Parses [json] and returns a validated [LayoutDefinition].
     *
     * @throws LayoutParseException if the JSON is malformed, a required field is
     *   missing, a direction value is unrecognised, or any row's width fractions
     *   sum to more than 1.0 + [WIDTH_EPSILON].
     */
    fun parse(json: String): LayoutDefinition {
        val root = JsonReader(json).readObject()

        val id        = root.string("id")
        val locale    = root.string("locale")
        val direction = parseDirection(root.string("direction"))
        val rowsRaw   = root.array("rows")

        val rows = rowsRaw.mapIndexed { rowIndex, rowValue ->
            val rowObj = rowValue as? Map<*, *>
                ?: throw LayoutParseException("row[$rowIndex] is not an object")
            @Suppress("UNCHECKED_CAST")
            val keysRaw = (rowObj["keys"] as? List<*>)
                ?: throw LayoutParseException("row[$rowIndex] missing 'keys' array")

            val keys = keysRaw.mapIndexed { keyIndex, keyValue ->
                val keyObj = keyValue as? Map<*, *>
                    ?: throw LayoutParseException("row[$rowIndex].key[$keyIndex] is not an object")
                parseKeyDef(rowIndex, keyIndex, keyObj)
            }

            validateRowWidth(rowIndex, keys)
            Row(keys)
        }

        return LayoutDefinition(id = id, locale = locale, direction = direction, rows = rows)
    }

    /**
     * Expands a [LayoutDefinition] into a flat list of [KeyDescriptor]s.
     *
     * Each descriptor carries fractional left/right edges computed from the cumulative
     * sum of width fractions within the row. This is the "round-trip to keyboard-engine
     * descriptors" step referenced in the T0.3 acceptance criteria.
     */
    fun toDescriptors(def: LayoutDefinition): List<KeyDescriptor> {
        val result = mutableListOf<KeyDescriptor>()
        def.rows.forEachIndexed { rowIndex, row ->
            var cursor = 0.0f
            row.keys.forEachIndexed { keyIndex, keyDef ->
                result += KeyDescriptor(
                    keyDef   = keyDef,
                    rowIndex = rowIndex,
                    keyIndex = keyIndex,
                    left     = cursor,
                    right    = cursor + keyDef.width,
                )
                cursor += keyDef.width
            }
        }
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseDirection(raw: String): Direction =
        when (raw.uppercase()) {
            "LTR" -> Direction.LTR
            "RTL" -> Direction.RTL
            else  -> throw LayoutParseException("Unknown direction '$raw'; expected LTR or RTL")
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseKeyDef(rowIndex: Int, keyIndex: Int, obj: Map<*, *>): KeyDef {
        val location = "row[$rowIndex].key[$keyIndex]"

        val codeRaw   = obj["code"] ?: throw LayoutParseException("$location missing 'code'")
        val label     = (obj["label"] as? String)
            ?: throw LayoutParseException("$location missing 'label'")
        val widthRaw  = obj["width"] ?: throw LayoutParseException("$location missing 'width'")
        val isSpecial = (obj["isSpecial"] as? Boolean) ?: false
        val moreKeys  = (obj["moreKeys"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        val code = when (codeRaw) {
            is String -> resolveSpecialCode(location, codeRaw)
            is Number -> codeRaw.toInt()
            else      -> throw LayoutParseException("$location 'code' must be a string or integer")
        }

        val width = when (widthRaw) {
            is Number -> widthRaw.toFloat()
            else      -> throw LayoutParseException("$location 'width' must be a number")
        }

        if (width <= 0.0f) {
            throw LayoutParseException("$location 'width' must be > 0, got $width")
        }

        return KeyDef(
            code      = code,
            label     = label,
            moreKeys  = moreKeys,
            width     = width,
            isSpecial = isSpecial,
        )
    }

    private fun resolveSpecialCode(location: String, name: String): Int =
        when (name.uppercase()) {
            "SHIFT"            -> SpecialCode.SHIFT
            "DELETE"           -> SpecialCode.DELETE
            "SYMBOLS"          -> SpecialCode.SYMBOLS
            "ENTER"            -> SpecialCode.ENTER
            "SPACE"            -> SpecialCode.SPACE
            "GLOBE"            -> SpecialCode.GLOBE
            "ALPHA"            -> SpecialCode.ALPHA
            "NUMERIC"          -> SpecialCode.NUMERIC
            "NUMBER_ROW_TOGGLE" -> SpecialCode.NUMBER_ROW_TOGGLE
            else               -> throw LayoutParseException("$location unknown special code '$name'")
        }

    /**
     * Rejects rows whose width fractions sum to more than 1.0 (within floating-point tolerance).
     *
     * Rows that sum to less than 1.0 are accepted: the remaining space becomes implicit
     * padding at the row edges.
     */
    private fun validateRowWidth(rowIndex: Int, keys: List<KeyDef>) {
        val total = keys.fold(0.0f) { acc, k -> acc + k.width }
        if (total > 1.0f + WIDTH_EPSILON) {
            throw LayoutParseException(
                "row[$rowIndex] width fractions sum to $total, which exceeds 1.0"
            )
        }
    }

    // ── Minimal JSON reader ───────────────────────────────────────────────────
    //
    // This handles the strict subset of JSON used by layout files. It is intentionally
    // not a general-purpose parser: numbers, strings, booleans, arrays, and objects are
    // enough; null, nested-array, and unicode escapes beyond BMP are out of scope.

    private class JsonReader(private val src: String) {
        private var pos = 0

        fun readObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = readValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek() == ',' -> pos++
                    peek() == '}' -> { pos++; break }
                    else          -> throw LayoutParseException("Expected ',' or '}' at pos $pos")
                }
            }
            return map
        }

        private fun readValue(): Any? {
            skipWhitespace()
            return when {
                peek() == '"'  -> readString()
                peek() == '{'  -> readObject()
                peek() == '['  -> readArray()
                peek() == 't' || peek() == 'f' -> readBoolean()
                peek() == 'n'  -> readNull()
                else           -> readNumber()
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWhitespace()
                list += readValue()
                skipWhitespace()
                when {
                    peek() == ',' -> pos++
                    peek() == ']' -> { pos++; break }
                    else          -> throw LayoutParseException("Expected ',' or ']' at pos $pos")
                }
            }
            return list
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"'  -> return sb.toString()
                    ch == '\\' -> sb.append(readEscape())
                    else       -> sb.append(ch)
                }
            }
            throw LayoutParseException("Unterminated string")
        }

        private fun readEscape(): Char {
            if (pos >= src.length) throw LayoutParseException("Truncated escape sequence")
            return when (val esc = src[pos++]) {
                '"'  -> '"'
                '\\' -> '\\'
                '/'  -> '/'
                'n'  -> '\n'
                'r'  -> '\r'
                't'  -> '\t'
                'b'  -> '\b'
                'u'  -> {
                    val hex = src.substring(pos, minOf(pos + 4, src.length))
                    if (hex.length < 4) throw LayoutParseException("Short unicode escape")
                    pos += 4
                    hex.toInt(16).toChar()
                }
                else -> throw LayoutParseException("Unknown escape '\\$esc'")
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) {
                pos++
            }
            val raw = src.substring(start, pos)
            return if ('.' in raw || 'e' in raw.lowercase()) raw.toDouble() else raw.toLong()
        }

        private fun readBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4; true
            } else if (src.startsWith("false", pos)) {
                pos += 5; false
            } else {
                throw LayoutParseException("Expected boolean at pos $pos")
            }
        }

        private fun readNull(): Nothing? {
            if (src.startsWith("null", pos)) { pos += 4; return null }
            throw LayoutParseException("Expected null at pos $pos")
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else ' '

        private fun expect(ch: Char) {
            if (pos >= src.length || src[pos] != ch) {
                throw LayoutParseException("Expected '$ch' at pos $pos, got '${if (pos < src.length) src[pos] else "EOF"}'")
            }
            pos++
        }
    }
}

/** Thrown when a layout file cannot be parsed or fails validation. */
class LayoutParseException(message: String) : RuntimeException(message)

// ── Extension helpers used by JsonReader ──────────────────────────────────────

private fun Map<*, *>.string(key: String): String =
    (this[key] as? String)
        ?: throw LayoutParseException("Required field '$key' is missing or not a string")

private fun Map<*, *>.array(key: String): List<*> =
    (this[key] as? List<*>)
        ?: throw LayoutParseException("Required field '$key' is missing or not an array")
