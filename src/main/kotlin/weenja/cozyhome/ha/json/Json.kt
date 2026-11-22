package weenja.cozyhome.ha.json

import kotlin.math.abs
import kotlin.math.floor

/**
 * An immutable JSON tree with a parser ([parse]) and a writer ([toString]), so the library needs no JSON dependency.
 *
 * Reading is forgiving on purpose: the same attribute can arrive as `21.5` from one integration and as `"21.5"` from
 * another. The accessors ([string], [double], [bool]) convert between scalars instead of failing, and the lookups
 * ([get], [str], [num], ...) return null or an empty list for anything missing.
 */
sealed class Json {

    /** The JSON literal `null`. */
    data object Null : Json()

    /** `true` or `false`. */
    data class Bool(val value: Boolean) : Json()

    /**
     * A JSON number. Stored as a [Double], like JavaScript does, so integers above 2^53 lose
     * precision. Home Assistant sends identifiers as strings, so this does not matter in practice.
     */
    data class Num(val value: Double) : Json()

    /** A JSON string. */
    data class Str(val value: String) : Json()

    /** A JSON array. */
    data class Arr(val items: List<Json>) : Json()

    /** A JSON object. Keys keep the order they were parsed or built in, so written output is stable. */
    data class Obj(val map: Map<String, Json>) : Json()

    /**
     * The member [key] of an object, or the element at index [key] (`"0"`, `"1"`, ...) of an array.
     * Null when there is no such member or element, or when this is a scalar.
     */
    operator fun get(key: String): Json? {
        return when (this) {
            is Obj -> map[key]
            is Arr -> key.toIntOrNull()?.let { index -> items.getOrNull(index) }
            else -> null
        }
    }

    /** The element at [index] of an array; null when out of range or when this is not an array. */
    fun at(index: Int): Json? = (this as? Arr)?.items?.getOrNull(index)

    /**
     * The text of a string, number or boolean; null for `null`, objects and arrays.
     *
     * Whole numbers are written without a fraction (`3`, not `3.0`), because Home Assistant uses
     * numbers for things like brightness and colour temperature that are shown or sent back as text.
     */
    val string: String?
        get() = when (this) {
            is Str -> value
            is Num -> formatNumber(value)
            is Bool -> value.toString()
            else -> null
        }

    /** The value of a number, of a string that holds a number, or of a boolean (1 or 0); else null. */
    val double: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.trim().toDoubleOrNull()
            is Bool -> if (value) 1.0 else 0.0
            else -> null
        }

    /**
     * The value of a boolean, of a non-zero number, or of a string Home Assistant uses for one:
     * `true`/`on`/`yes` and `false`/`off`/`no`, in any case. Null for anything else.
     */
    val bool: Boolean?
        get() = when (this) {
            is Bool -> value
            is Str -> parseBooleanWord(value)
            is Num -> value != 0.0
            else -> null
        }

    /** The elements of an array; empty when this is not an array. */
    val list: List<Json>
        get() = (this as? Arr)?.items ?: emptyList()

    /** The members of an object; empty when this is not an object. */
    val obj: Map<String, Json>
        get() = (this as? Obj)?.map ?: emptyMap()

    /** True for the JSON literal `null` (not for a missing member, which is a Kotlin null). */
    val isNull: Boolean
        get() = this is Null

    /** Shorthand for `get(key)?.string`. */
    fun str(key: String): String? = get(key)?.string

    /** Shorthand for `get(key)?.double`. */
    fun num(key: String): Double? = get(key)?.double

    /** Shorthand for `get(key)?.bool`. */
    fun flag(key: String): Boolean? = get(key)?.bool

    /** The elements of the array member [key]; empty when it is missing or not an array. */
    fun arr(key: String): List<Json> = get(key)?.list ?: emptyList()

    /** The string elements of the array member [key]. Elements that are not strings are skipped. */
    fun strings(key: String): List<String> = arr(key).mapNotNull { (it as? Str)?.value }

    /** This tree as compact JSON text. Final, so the data subclasses do not generate their own. */
    final override fun toString(): String {
        val out = StringBuilder()
        write(out)
        return out.toString()
    }

    /**
     * Appends this tree as compact JSON to [out].
     *
     * NaN and infinities have no JSON form and are written as `null`, so the output is always valid.
     */
    fun write(out: StringBuilder) {
        when (this) {
            is Null -> out.append("null")
            is Bool -> out.append(value)
            is Num -> out.append(if (value.isFinite()) formatNumber(value) else "null")
            is Str -> quote(value, out)
            is Arr -> writeArray(items, out)
            is Obj -> writeObject(map, out)
        }
    }

    companion object {

        /** Whole numbers below this size are written without a fraction; larger ones keep the exponent form. */
        private const val LARGEST_PLAIN_WHOLE_NUMBER = 1e15

        /**
         * Parses [text] as one JSON value ([RFC 8259](https://www.rfc-editor.org/rfc/rfc8259)).
         *
         * @throws JsonException when [text] is not valid JSON, with the character offset of the problem.
         */
        fun parse(text: String): Json = JsonParser(text).parseDocument()

        /** Parses [text], or returns null when it is null, blank or not valid JSON. */
        fun parseOrNull(text: String?): Json? {
            if (text.isNullOrBlank()) {
                return null
            }
            return try {
                parse(text)
            } catch (_: JsonException) {
                null
            }
        }

        /**
         * Converts a Kotlin value to JSON: null, [Json], booleans, numbers, strings, maps (keys are
         * converted with `toString()`), iterables and arrays.
         *
         * @throws IllegalArgumentException for any other type, rather than guessing at a representation.
         */
        fun of(value: Any?): Json {
            return when (value) {
                null -> Null
                is Json -> value
                is Boolean -> Bool(value)
                is Number -> Num(value.toDouble())
                is String -> Str(value)
                is Map<*, *> -> Obj(value.entries.associate { (key, member) -> key.toString() to of(member) })
                is Iterable<*> -> Arr(value.map { of(it) })
                is Array<*> -> Arr(value.map { of(it) })
                else -> throw IllegalArgumentException("Cannot convert ${value::class.java.name} to JSON")
            }
        }

        /** Builds an object, converting each value with [of]: `Json.obj("type" to "ping", "id" to 4)`. */
        fun obj(vararg pairs: Pair<String, Any?>): Obj {
            return Obj(pairs.associate { (key, value) -> key to of(value) })
        }

        /** Builds an array, converting each item with [of]. */
        fun arr(items: List<Any?>): Arr = Arr(items.map { of(it) })

        private fun quote(text: String, out: StringBuilder) {
            out.append('"')
            for (char in text) {
                appendEscaped(char, out)
            }
            out.append('"')
        }

        private fun appendEscaped(char: Char, out: StringBuilder) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> appendPlainOrUnicodeEscape(char, out)
            }
        }

        private fun appendPlainOrUnicodeEscape(char: Char, out: StringBuilder) {
            if (char >= ' ') {
                out.append(char)
                return
            }
            // Other control characters have no short escape, and RFC 8259 forbids them raw.
            out.append("\\u")
            out.append(char.code.toString(radix = 16).padStart(length = 4, padChar = '0'))
        }

        private fun formatNumber(value: Double): String {
            val isWhole = value == floor(value)
            if (isWhole && abs(value) < LARGEST_PLAIN_WHOLE_NUMBER) {
                return value.toLong().toString()
            }
            return value.toString()
        }

        private fun parseBooleanWord(word: String): Boolean? {
            return when (word.lowercase()) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> null
            }
        }

        private fun writeArray(items: List<Json>, out: StringBuilder) {
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    out.append(',')
                }
                item.write(out)
            }
            out.append(']')
        }

        private fun writeObject(members: Map<String, Json>, out: StringBuilder) {
            out.append('{')
            var isFirst = true
            for ((key, member) in members) {
                if (!isFirst) {
                    out.append(',')
                }
                isFirst = false
                quote(key, out)
                out.append(':')
                member.write(out)
            }
            out.append('}')
        }
    }
}

/** Thrown by [Json.parse] when the text is not valid JSON. The message names the character offset. */
class JsonException(message: String) : Exception(message)
