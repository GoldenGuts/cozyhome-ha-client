package weenja.cozyhome.ha.model

import weenja.cozyhome.ha.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityStateTest {

    /** Trimmed from a real `get_states` result; `context` is left in to show unknown keys are ignored. */
    private val getStatesResult = """
        [
          {
            "entity_id": "light.living_room_lamp",
            "state": "on",
            "attributes": {
              "supported_color_modes": ["color_temp", "xy"],
              "color_mode": "color_temp",
              "brightness": 128,
              "color_temp_kelvin": 2700,
              "friendly_name": "Living room lamp",
              "supported_features": 40
            },
            "last_changed": "2026-11-20T18:02:11.482913+00:00",
            "last_reported": "2026-11-20T18:02:11.482913+00:00",
            "last_updated": "2026-11-20T18:02:11.482913+00:00",
            "context": {"id": "01JD4X2M7Q", "parent_id": null, "user_id": "8f3c"}
          },
          {
            "entity_id": "sensor.kitchen_temperature",
            "state": "21.4",
            "attributes": {
              "state_class": "measurement",
              "unit_of_measurement": "°C",
              "device_class": "temperature",
              "friendly_name": "Kitchen Temperature"
            },
            "last_changed": "2026-11-20T18:10:00.000000+00:00",
            "last_updated": "2026-11-20T18:10:00.000000+00:00"
          },
          {
            "entity_id": "binary_sensor.front_door",
            "state": "unavailable",
            "attributes": {"device_class": "door", "icon": "mdi:door", "friendly_name": ""},
            "last_changed": "2026-11-19T07:45:30+00:00",
            "last_updated": "2026-11-19T07:45:30+00:00"
          },
          {"state": "on", "attributes": {}},
          {"entity_id": "no_dot", "state": "on"}
        ]
    """.trimIndent()

    @Test
    fun `parses a get_states result and skips entries without a valid entity ID`() {
        val states = EntityState.parseList(getStatesResult)

        assertEquals(
            listOf("light.living_room_lamp", "sensor.kitchen_temperature", "binary_sensor.front_door"),
            states.map { it.id },
        )
    }

    @Test
    fun `reads a light`() {
        val lamp = EntityState.parseList(getStatesResult)[0]

        assertEquals("light", lamp.domain)
        assertEquals("living_room_lamp", lamp.objectId)
        assertEquals("Living room lamp", lamp.name)
        assertTrue(lamp.isOn)
        assertTrue(lamp.isAvailable)
        assertEquals(50, lamp.brightnessPercent)
        assertEquals(2700.0, lamp.attrDouble("color_temp_kelvin"))
        assertEquals("color_temp", lamp.attrString("color_mode"))
        assertEquals(2, lamp.attr("supported_color_modes")?.list?.size)
        assertEquals("2026-11-20T18:02:11.482913+00:00", lamp.lastChanged)
    }

    @Test
    fun `reads a numeric sensor`() {
        val sensor = EntityState.parseList(getStatesResult)[1]

        assertEquals(21.4, sensor.number)
        assertEquals("°C", sensor.unit)
        assertEquals("temperature", sensor.deviceClass)
        assertFalse(sensor.isOn)
        assertNull(sensor.brightnessPercent)
    }

    @Test
    fun `an unavailable entity with a blank friendly name falls back to its object ID`() {
        val door = EntityState.parseList(getStatesResult)[2]

        assertFalse(door.isAvailable)
        assertNull(door.number)
        assertEquals("Front door", door.name)
        assertEquals("mdi:door", door.icon)
    }

    @Test
    fun `unknown is not available either`() {
        val state = EntityState("sensor.power", "unknown", Json.Obj(emptyMap()))

        assertFalse(state.isAvailable)
    }

    @Test
    fun `missing fields get safe defaults`() {
        val state = EntityState.from(Json.parse("""{"entity_id":"switch.heater"}""")) ?: error("not parsed")

        assertEquals("unknown", state.state)
        assertEquals(Json.Obj(emptyMap()), state.attributes)
        assertEquals("", state.lastChanged)
        assertEquals("", state.unit)
        assertNull(state.attrBool("anything"))
    }

    @Test
    fun `toJson reads back to an equal state`() {
        val original = EntityState.parseList(getStatesResult)[0]

        val restored = EntityState.from(Json.parse(original.toJson().toString()))

        assertEquals(original, restored)
    }

    @Test
    fun `parseList of something other than an array is empty`() {
        assertEquals(emptyList(), EntityState.parseList("""{"message":"not a list"}"""))
    }
}
