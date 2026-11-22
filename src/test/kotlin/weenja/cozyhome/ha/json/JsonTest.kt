package weenja.cozyhome.ha.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun `get reads object members and array elements`() {
        val parsed = Json.parse("""{"list":["a","b"]}""")

        assertEquals(Json.Str("b"), parsed["list"]?.get("1"))
        assertNull(parsed["list"]?.get("2"))
        assertNull(parsed["list"]?.get("first"))
        assertNull(parsed["missing"])
        assertNull(Json.Str("text")["anything"])
    }

    @Test
    fun `at reads array elements only`() {
        val array = Json.parse("[10,20]")

        assertEquals(20.0, array.at(1)?.double)
        assertNull(array.at(2))
        assertNull(array.at(-1))
        assertNull(Json.parse("""{"0":1}""").at(0))
    }

    @Test
    fun `string gives the text of scalars and null for containers`() {
        assertEquals("on", Json.Str("on").string)
        assertEquals("3", Json.Num(3.0).string)
        assertEquals("3.5", Json.Num(3.5).string)
        assertEquals("-40", Json.Num(-40.0).string)
        assertEquals("true", Json.Bool(true).string)
        assertNull(Json.Null.string)
        assertNull(Json.Arr(emptyList()).string)
        assertNull(Json.Obj(emptyMap()).string)
    }

    @Test
    fun `string keeps the exponent for very large whole numbers`() {
        assertEquals("999999999999999", Json.Num(999_999_999_999_999.0).string)
        assertEquals("1.0E15", Json.Num(1e15).string)
    }

    @Test
    fun `double reads numbers, numeric strings and booleans`() {
        assertEquals(21.5, Json.Num(21.5).double)
        assertEquals(21.5, Json.Str(" 21.5 ").double)
        assertEquals(1.0, Json.Bool(true).double)
        assertEquals(0.0, Json.Bool(false).double)
        assertNull(Json.Str("unavailable").double)
        assertNull(Json.Null.double)
    }

    @Test
    fun `bool understands the words Home Assistant uses`() {
        for (word in listOf("true", "on", "yes", "ON", "Yes")) {
            assertEquals(true, Json.Str(word).bool, word)
        }
        for (word in listOf("false", "off", "no", "OFF")) {
            assertEquals(false, Json.Str(word).bool, word)
        }
        assertNull(Json.Str("maybe").bool)
        assertEquals(true, Json.Num(2.0).bool)
        assertEquals(false, Json.Num(0.0).bool)
        assertNull(Json.Null.bool)
    }

    @Test
    fun `container views are empty for the wrong type`() {
        assertEquals(emptyList(), Json.Str("x").list)
        assertEquals(emptyMap(), Json.Arr(emptyList()).obj)
        assertEquals(emptyList(), Json.parse("""{"a":1}""").arr("a"))
        assertEquals(emptyList(), Json.parse("{}").arr("a"))
    }

    @Test
    fun `isNull tells the null literal from a missing member`() {
        val parsed = Json.parse("""{"area_id":null}""")

        assertTrue(parsed["area_id"]?.isNull == true)
        assertNull(parsed["floor_id"])
        assertNull(parsed.str("area_id"))
        assertFalse(Json.Str("null").isNull)
    }

    @Test
    fun `member shorthands convert like the accessors`() {
        val parsed = Json.parse("""{"name":"Lamp","brightness":"128","hidden":"off","labels":["a",1,"b",null]}""")

        assertEquals("Lamp", parsed.str("name"))
        assertEquals(128.0, parsed.num("brightness"))
        assertEquals(false, parsed.flag("hidden"))
        assertEquals(listOf("a", "b"), parsed.strings("labels"))
    }

    @Test
    fun `toString writes compact JSON`() {
        val built = Json.obj(
            "id" to 7,
            "type" to "call_service",
            "service_data" to Json.obj("brightness_pct" to 40.5, "entity_id" to listOf("light.a", "light.b")),
            "return_response" to false,
            "target" to null,
        )

        val expected = """{"id":7,"type":"call_service","service_data":{"brightness_pct":40.5,""" +
            """"entity_id":["light.a","light.b"]},"return_response":false,"target":null}"""
        assertEquals(expected, built.toString())
    }

    @Test
    fun `toString escapes what JSON requires`() {
        val text = "quote\" backslash\\ \n\r\t\b\u000C \u0001 é 💡"

        assertEquals("\"quote\\\" backslash\\\\ \\n\\r\\t\\b\\f \\u0001 é 💡\"", Json.Str(text).toString())
    }

    @Test
    fun `toString writes numbers without a JSON form as null`() {
        val unwritable = Json.arr(listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))

        assertEquals("[null,null,null]", unwritable.toString())
    }

    @Test
    fun `of converts Kotlin values`() {
        assertEquals(Json.Num(3.0), Json.of(3))
        assertEquals(Json.Num(3.0), Json.of(3L))
        assertEquals(Json.Num(1.5), Json.of(1.5f))
        assertEquals(Json.Null, Json.of(null))
        assertEquals(Json.Arr(listOf(Json.Str("a"))), Json.of(arrayOf("a")))
        assertEquals(Json.obj("1" to true), Json.of(mapOf(1 to true)))
        assertEquals(Json.Str("x"), Json.of(Json.Str("x")))
    }

    @Test
    fun `of refuses types it has no JSON form for`() {
        assertFailsWith<IllegalArgumentException> { Json.of(Any()) }
    }

    @Test
    fun `round trip through text keeps the tree`() {
        val original = """{"a":1,"b":[true,false,null,"x\"y\n"],"c":{"d":-2.5e3,"e":0.001},"f":"é ✓ 💡","g":[]}"""

        val parsed = Json.parse(original)
        val reparsed = Json.parse(parsed.toString())

        assertEquals(parsed, reparsed)
        assertEquals(parsed.toString(), reparsed.toString())
        assertEquals(-2500.0, reparsed["c"]?.num("d"))
        assertEquals("x\"y\n", reparsed["b"]?.at(3)?.string)
    }

    @Test
    fun `trees with the same content are equal`() {
        assertEquals(Json.parse("""{"a":[1,"b"]}"""), Json.obj("a" to listOf(1, "b")))
    }
}
