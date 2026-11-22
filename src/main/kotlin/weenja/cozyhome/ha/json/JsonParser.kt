package weenja.cozyhome.ha.json

/**
 * A recursive-descent parser for one JSON document, following the grammar of
 * [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259).
 *
 * It is strict where leniency would hide broken input (number syntax, escapes, raw control
 * characters, trailing text), because a half-understood message from Home Assistant is worse than
 * a clear error. Nesting is limited to [MAX_DEPTH] levels so that hostile input fails with a
 * [JsonException] instead of a [StackOverflowError].
 *
 * One instance parses one text; it is not thread-safe and not reusable.
 */
internal class JsonParser(private val text: String) {

    private var position = 0
    private var depth = 0

    /** Parses the whole text as a single value; whitespace around it is allowed, anything else is not. */
    fun parseDocument(): Json {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (position != text.length) {
            fail("unexpected text after the value")
        }
        return value
    }

    private fun parseValue(): Json {
        if (isAtEnd()) {
            fail("unexpected end of input")
        }
        val char = text[position]
        return when {
            char == '{' -> parseNested { parseObject() }
            char == '[' -> parseNested { parseArray() }
            char == '"' -> Json.Str(parseString())
            char == 't' -> parseLiteral("true", Json.Bool(true))
            char == 'f' -> parseLiteral("false", Json.Bool(false))
            char == 'n' -> parseLiteral("null", Json.Null)
            char == '-' || char.isAsciiDigit() -> parseNumber()
            else -> fail("unexpected character '$char'")
        }
    }

    private fun parseNested(parseContainer: () -> Json): Json {
        depth++
        if (depth > MAX_DEPTH) {
            fail("nesting deeper than $MAX_DEPTH levels")
        }
        val container = parseContainer()
        depth--
        return container
    }

    private fun parseLiteral(word: String, value: Json): Json {
        if (!text.startsWith(word, position)) {
            fail("invalid literal, expected '$word'")
        }
        position += word.length
        return value
    }

    private fun parseObject(): Json {
        expect('{')
        val members = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (consumeIf('}')) {
            return Json.Obj(members)
        }
        do {
            skipWhitespace()
            if (peek() != '"') {
                fail("expected a member name in quotes")
            }
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            members[key] = parseValue()
            skipWhitespace()
        } while (consumeIf(','))
        expect('}')
        return Json.Obj(members)
    }

    private fun parseArray(): Json {
        expect('[')
        val items = ArrayList<Json>()
        skipWhitespace()
        if (consumeIf(']')) {
            return Json.Arr(items)
        }
        do {
            skipWhitespace()
            items.add(parseValue())
            skipWhitespace()
        } while (consumeIf(','))
        expect(']')
        return Json.Arr(items)
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (true) {
            if (isAtEnd()) {
                fail("unterminated string")
            }
            val char = text[position]
            position++
            when {
                char == '"' -> return value.toString()
                char == '\\' -> value.append(parseEscape())
                char < ' ' -> fail("control character in string must be escaped")
                else -> value.append(char)
            }
        }
    }

    /**
     * Decodes the escape after a backslash. A `\uXXXX` escape yields one UTF-16 unit, so a character
     * outside the Basic Multilingual Plane, sent as a surrogate pair of two escapes, comes out right
     * because Kotlin strings are UTF-16 too.
     */
    private fun parseEscape(): Char {
        if (isAtEnd()) {
            fail("unterminated escape")
        }
        val escape = text[position]
        position++
        return when (escape) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail("invalid escape '\\$escape'")
        }
    }

    private fun parseUnicodeEscape(): Char {
        val end = position + UNICODE_ESCAPE_LENGTH
        if (end > text.length) {
            fail("unicode escape needs $UNICODE_ESCAPE_LENGTH hex digits")
        }
        val digits = text.substring(position, end)
        // toIntOrNull alone would also accept a sign, as in "\u+041".
        if (!digits.all { it.isHexDigit() }) {
            fail("unicode escape needs $UNICODE_ESCAPE_LENGTH hex digits")
        }
        position = end
        return digits.toInt(radix = 16).toChar()
    }

    /** Reads `-? int frac? exp?` exactly as RFC 8259 defines it, then converts the matched text. */
    private fun parseNumber(): Json {
        val start = position
        consumeIf('-')
        readIntegerPart()
        if (consumeIf('.')) {
            readDigits("expected a digit after the decimal point")
        }
        if (consumeIf('e') || consumeIf('E')) {
            if (!consumeIf('+')) {
                consumeIf('-')
            }
            readDigits("expected a digit in the exponent")
        }
        return Json.Num(text.substring(start, position).toDouble())
    }

    private fun readIntegerPart() {
        // JSON forbids leading zeros: "0" and "0.5" are numbers, "01" is not.
        if (consumeIf('0')) {
            if (peek()?.isAsciiDigit() == true) {
                fail("leading zeros are not allowed")
            }
            return
        }
        readDigits("expected a digit")
    }

    private fun readDigits(errorMessage: String) {
        if (peek()?.isAsciiDigit() != true) {
            fail(errorMessage)
        }
        while (peek()?.isAsciiDigit() == true) {
            position++
        }
    }

    private fun skipWhitespace() {
        while (peek()?.isJsonWhitespace() == true) {
            position++
        }
    }

    private fun expect(expected: Char) {
        if (!consumeIf(expected)) {
            val found = peek()?.let { "'$it'" } ?: "end of input"
            fail("expected '$expected' but found $found")
        }
    }

    private fun consumeIf(expected: Char): Boolean {
        if (peek() != expected) {
            return false
        }
        position++
        return true
    }

    private fun peek(): Char? = text.getOrNull(position)

    private fun isAtEnd(): Boolean = position >= text.length

    private fun fail(problem: String): Nothing = throw JsonException("$problem at offset $position")

    private companion object {
        /** Far deeper than any Home Assistant message, shallow enough to stay clear of the thread stack limit. */
        const val MAX_DEPTH = 512

        const val UNICODE_ESCAPE_LENGTH = 4

        fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        fun Char.isHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'

        /** Only these four count as whitespace in JSON; Unicode spaces do not. */
        fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'
    }
}
