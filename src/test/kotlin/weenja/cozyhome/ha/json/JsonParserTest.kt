package weenja.cozyhome.ha.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonParserTest {

    @Test
    fun `parses every scalar type`() {
        assertEquals(Json.Null, Json.parse("null"))
        assertEquals(Json.Bool(true), Json.parse("true"))
        assertEquals(Json.Bool(false), Json.parse("false"))
        assertEquals(Json.Num(42.0), Json.parse("42"))
        assertEquals(Json.Str("hello"), Json.parse("\"hello\""))
    }

    @Test
    fun `allows whitespace around and between tokens`() {
        val parsed = Json.parse(" \n\t{ \"a\" :\r\n [ 1 , 2 ] }\n ")

        assertEquals(listOf(1.0, 2.0), parsed.arr("a").map { it.double })
    }

    @Test
    fun `parses nested objects and arrays`() {
        val parsed = Json.parse("""{"a":{"b":[{"c":[[],{}]}]}}""")

        val innermost = parsed["a"]?.get("b")?.at(0)?.get("c")
        assertEquals(Json.Arr(emptyList()), innermost?.at(0))
        assertEquals(Json.Obj(emptyMap()), innermost?.at(1))
    }

    @Test
    fun `keeps member order`() {
        val parsed = Json.parse("""{"zebra":1,"apple":2,"mango":3}""")

        assertEquals(listOf("zebra", "apple", "mango"), parsed.obj.keys.toList())
    }

    @Test
    fun `the last of duplicate members wins`() {
        assertEquals(2.0, Json.parse("""{"a":1,"a":2}""").num("a"))
    }

    @Test
    fun `parses numbers in every form the grammar allows`() {
        val expectedByText = mapOf(
            "0" to 0.0,
            "-0" to -0.0,
            "7" to 7.0,
            "-12" to -12.0,
            "3.25" to 3.25,
            "0.5" to 0.5,
            "1e3" to 1000.0,
            "1E3" to 1000.0,
            "2.5e+2" to 250.0,
            "-2.5e-3" to -0.0025,
            "6.02E23" to 6.02e23,
        )
        for ((text, expected) in expectedByText) {
            assertEquals(Json.Num(expected), Json.parse(text), "parsing $text")
        }
    }

    @Test
    fun `rejects numbers outside the grammar`() {
        val invalidNumbers = listOf("01", "-", "1.", ".5", "+1", "1e", "1e+", "--1", "1.2.3", "0x10", "NaN", "Infinity")
        for (text in invalidNumbers) {
            assertNull(Json.parseOrNull(text), "$text should not parse")
        }
    }

    @Test
    fun `decodes the short escapes`() {
        val parsed = Json.parse(""""quote\" backslash\\ slash\/ \b\f\n\r\t"""")

        assertEquals("quote\" backslash\\ slash/ \b\u000C\n\r\t", parsed.string)
    }

    @Test
    fun `decodes unicode escapes in either case`() {
        assertEquals("é", Json.parse("\"\\u00e9\"").string)
        assertEquals("é", Json.parse("\"\\u00E9\"").string)
        assertEquals("°C", Json.parse("\"\\u00b0C\"").string)
    }

    @Test
    fun `joins a surrogate pair into one character outside the basic plane`() {
        val parsed = Json.parse("\"\\ud83d\\udca1 on\"").string

        assertEquals("\uD83D\uDCA1 on", parsed)
        assertEquals(0x1F4A1, parsed?.codePointAt(0))
    }

    @Test
    fun `passes raw non-ASCII text through`() {
        assertEquals("Küche ✓ 💡", Json.parse("\"Küche ✓ 💡\"").string)
    }

    @Test
    fun `rejects malformed escapes`() {
        val invalidStrings = listOf("\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\u+041\"", "\"\\u-001\"", "\"\\")
        for (text in invalidStrings) {
            assertNull(Json.parseOrNull(text), "$text should not parse")
        }
    }

    @Test
    fun `rejects raw control characters inside strings`() {
        assertNull(Json.parseOrNull("\"line\nbreak\""))
        assertNull(Json.parseOrNull("\"\u0001\""))
    }

    @Test
    fun `rejects structurally broken documents`() {
        val invalidDocuments = listOf(
            "{broken",
            "{\"a\":1",
            "{\"a\" 1}",
            "{a:1}",
            "{\"a\":1,}",
            "[1,]",
            "[1 2]",
            "[",
            "]",
            "\"unterminated",
            "tru",
            "nul",
            "{} {}",
            "1 2",
            "'single'",
        )
        for (text in invalidDocuments) {
            assertNull(Json.parseOrNull(text), "$text should not parse")
        }
    }

    @Test
    fun `parseOrNull returns null for null and blank text`() {
        assertNull(Json.parseOrNull(null))
        assertNull(Json.parseOrNull(""))
        assertNull(Json.parseOrNull("   "))
    }

    @Test
    fun `parse reports the offset of the problem`() {
        val error = assertFailsWith<JsonException> { Json.parse("[1, x]") }

        assertTrue(error.message.orEmpty().endsWith("at offset 4"), error.message)
    }

    @Test
    fun `fails cleanly on nesting too deep to parse safely`() {
        val levels = 100_000
        val deeplyNested = "[".repeat(levels) + "]".repeat(levels)

        assertFailsWith<JsonException> { Json.parse(deeplyNested) }
    }

    @Test
    fun `accepts nesting within the limit`() {
        val levels = 500
        val nested = "[".repeat(levels) + "]".repeat(levels)

        assertEquals(nested, Json.parse(nested).toString())
    }
}
