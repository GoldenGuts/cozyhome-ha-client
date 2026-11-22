package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaMessagesTest {
    @Test
    fun `auth message carries the access token`() {
        assertEquals("{\"type\":\"auth\",\"access_token\":\"secret\"}", HaMessages.auth("secret"))
    }

    @Test
    fun `command puts id and type before the fields`() {
        val command = HaCommand("subscribe_events", Json.obj("event_type" to "lovelace_updated"))

        val text = HaMessages.command(7, command)

        assertEquals("{\"id\":7,\"type\":\"subscribe_events\",\"event_type\":\"lovelace_updated\"}", text)
    }

    @Test
    fun `command without fields has only id and type`() {
        assertEquals("{\"id\":1,\"type\":\"get_states\"}", HaMessages.command(1, HaCommand("get_states")))
    }

    @Test
    fun `command fields cannot replace the id or the type`() {
        assertFailsWith<IllegalArgumentException> { HaCommand("get_states", Json.obj("id" to 1)) }
        assertFailsWith<IllegalArgumentException> { HaCommand("get_states", Json.obj("type" to "call_service")) }
    }

    @Test
    fun `parses auth_required with the server version`() {
        val message = HaMessages.parse("{\"type\":\"auth_required\",\"ha_version\":\"2026.11.1\"}").single()

        assertTrue(message.isAuthRequired)
        assertEquals("2026.11.1", message.haVersion)
    }

    @Test
    fun `parses the top-level message of auth_invalid`() {
        val message = HaMessages.parse("{\"type\":\"auth_invalid\",\"message\":\"Invalid access token\"}").single()

        assertTrue(message.isAuthInvalid)
        assertEquals("Invalid access token", message.errorMessage)
    }

    @Test
    fun `parses a successful result`() {
        val message = HaMessages.parse("{\"id\":3,\"type\":\"result\",\"success\":true,\"result\":{\"a\":1}}").single()

        assertTrue(message.isResult)
        assertTrue(message.success)
        assertEquals(3, message.id)
        assertEquals(1.0, message.result?.num("a"))
    }

    @Test
    fun `parses a failed result`() {
        val text = "{\"id\":4,\"type\":\"result\",\"success\":false," +
            "\"error\":{\"code\":\"unknown_command\",\"message\":\"Unknown command.\"}}"

        val message = HaMessages.parse(text).single()

        assertFalse(message.success)
        assertEquals("unknown_command", message.errorCode)
        assertEquals("Unknown command.", message.errorMessage)
    }

    @Test
    fun `splits coalesced messages`() {
        val text = "[{\"id\":2,\"type\":\"event\",\"event\":{\"event_type\":\"area_registry_updated\"}}," +
            "{\"id\":5,\"type\":\"result\",\"success\":true,\"result\":null}]"

        val messages = HaMessages.parse(text)

        assertEquals(2, messages.size)
        assertTrue(messages[0].isEvent)
        assertEquals("area_registry_updated", messages[0].event?.str("event_type"))
        assertTrue(messages[1].isResult)
        assertEquals(5, messages[1].id)
    }

    @Test
    fun `ignores text that is not JSON`() {
        assertTrue(HaMessages.parse("not json").isEmpty())
    }

    @Test
    fun `scan commands are read-only and common_control takes no options`() {
        val types = HaMessages.scanCommands.values.map { it.type }

        assertEquals("get_states", types.first())
        assertTrue(types.none { it.startsWith("call_service") || it.endsWith("/update") || it.endsWith("/delete") })
        assertNull(HaMessages.scanCommands.getValue("suggested").fields)
        assertEquals("home", HaMessages.scanCommands.getValue("favorites").fields?.str("key"))
    }

    @Test
    fun `call_service sends empty service data and omits return_response unless asked`() {
        val plain = HaMessages.command(1, HaMessages.callService("light", "turn_on", null))
        val withResponse = HaMessages.command(2, HaMessages.callService("weather", "get_forecasts", null, true))

        assertEquals(
            "{\"id\":1,\"type\":\"call_service\",\"domain\":\"light\",\"service\":\"turn_on\",\"service_data\":{}}",
            plain,
        )
        assertTrue(withResponse.endsWith(",\"return_response\":true}"))
    }

    @Test
    fun `statistic command asks for today's change`() {
        val text = HaMessages.command(9, HaMessages.statisticChangeToday("sensor.grid_import"))

        val expected = "{\"id\":9,\"type\":\"recorder/statistic_during_period\"," +
            "\"statistic_id\":\"sensor.grid_import\",\"calendar\":{\"period\":\"day\"},\"types\":[\"change\"]}"
        assertEquals(expected, text)
    }

    @Test
    fun `favorites are found wherever the frontend stored them`() {
        val result = Json.parse(
            "{\"value\":{\"favorite_entities\":[\"light.kitchen\",\"light.kitchen\"]," +
                "\"sections\":[{\"favorites\":[{\"entity_id\":\"climate.hall\"},\"not-an-entity\"]}]}}",
        )

        assertEquals(listOf("light.kitchen", "climate.hall"), HaMessages.favoritesOf(result))
        assertTrue(HaMessages.favoritesOf(null).isEmpty())
    }

    @Test
    fun `suggested entities come from entities, entity_ids or a bare array`() {
        val fromEntities = Json.parse("{\"entities\":[\"light.desk\",{\"entity_id\":\"fan.attic\"}]}")
        val fromIds = Json.parse("{\"entity_ids\":[\"switch.pump\"]}")
        val bare = Json.parse("[\"cover.garage\",\"junk\"]")

        assertEquals(listOf("light.desk", "fan.attic"), HaMessages.suggestedOf(fromEntities))
        assertEquals(listOf("switch.pump"), HaMessages.suggestedOf(fromIds))
        assertEquals(listOf("cover.garage"), HaMessages.suggestedOf(bare))
    }

    @Test
    fun `change is read from a statistic answer`() {
        assertEquals(12.5, HaMessages.changeOf(Json.parse("{\"change\":12.5}")))
        assertNull(HaMessages.changeOf(Json.parse("{\"change\":null}")))
        assertNull(HaMessages.changeOf(null))
    }
}
