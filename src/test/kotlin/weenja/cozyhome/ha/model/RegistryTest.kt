package weenja.cozyhome.ha.model

import weenja.cozyhome.ha.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {

    /** `config/floor_registry/list` */
    private val floorsResult = Json.parse(
        """
        [
          {"aliases": [], "floor_id": "upstairs", "icon": "mdi:home-floor-1", "level": 1, "name": "Upstairs",
           "created_at": 1731900000.0, "modified_at": 1731900000.0},
          {"aliases": [], "floor_id": "ground_floor", "icon": null, "level": 0, "name": "Ground floor"},
          {"aliases": [], "floor_id": "garden", "icon": null, "level": null, "name": "Garden"},
          {"name": "No ID"}
        ]
        """.trimIndent(),
    )

    /** `config/area_registry/list` */
    private val areasResult = Json.parse(
        """
        [
          {"aliases": [], "area_id": "living_room", "floor_id": "ground_floor", "humidity_entity_id": null,
           "icon": "mdi:sofa", "labels": ["cosy"], "name": "Living Room", "picture": null,
           "temperature_entity_id": "sensor.living_room_temperature"},
          {"aliases": [], "area_id": "kitchen", "floor_id": "ground_floor", "icon": null, "labels": [],
           "name": "Kitchen"},
          {"area_id": "bedroom", "floor_id": "upstairs", "name": "Bedroom",
           "humidity_entity_id": "sensor.bedroom_humidity"}
        ]
        """.trimIndent(),
    )

    /** `config/device_registry/list` */
    private val devicesResult = Json.parse(
        """
        [
          {"area_id": "living_room", "config_entries": ["01JC"], "disabled_by": null, "entry_type": null,
           "id": "4c1e2b9d", "manufacturer": "Signify Netherlands B.V.", "model": "Hue go", "name": "Hue go 1",
           "name_by_user": "Reading lamp", "sw_version": "1.122.2"},
          {"area_id": "kitchen", "disabled_by": null, "id": "7a0f3e11", "manufacturer": "Shelly",
           "model": "Shelly Plug S", "name": "Kitchen plug", "name_by_user": null},
          {"area_id": null, "disabled_by": "user", "id": "9b22c4aa", "manufacturer": null, "model": null,
           "name": "Old sensor"}
        ]
        """.trimIndent(),
    )

    /** `config/entity_registry/list_for_display` */
    private val entitiesResult = Json.parse(
        """
        {
          "entity_categories": {"0": "config", "1": "diagnostic"},
          "entities": [
            {"ei": "light.reading_lamp", "di": "4c1e2b9d", "pl": "hue", "lb": ["night"]},
            {"ei": "sensor.kitchen_plug_power", "di": "7a0f3e11", "pl": "shelly", "en": "Plug power"},
            {"ei": "sensor.kitchen_plug_rssi", "di": "7a0f3e11", "pl": "shelly", "ec": 1, "hb": true},
            {"ei": "switch.kitchen_plug_led", "di": "7a0f3e11", "pl": "shelly", "ec": 0},
            {"ei": "light.bedroom_ceiling", "ai": "bedroom", "di": "4c1e2b9d", "pl": "hue"},
            {"ei": "sensor.mystery", "ec": 7},
            {"di": "7a0f3e11"}
          ]
        }
        """.trimIndent(),
    )

    /** `config/label_registry/list` */
    private val labelsResult = Json.parse(
        """
        [
          {"color": "indigo", "description": null, "icon": "mdi:weather-night", "label_id": "night", "name": "Night"},
          {"color": null, "icon": null, "label_id": "cosy", "name": "Cosy"}
        ]
        """.trimIndent(),
    )

    private val registry = Registry.parse(areasResult, floorsResult, devicesResult, entitiesResult, labelsResult)

    @Test
    fun `parses floors`() {
        assertEquals(
            listOf(
                Floor("upstairs", "Upstairs", 1, "mdi:home-floor-1"),
                Floor("ground_floor", "Ground floor", 0, ""),
                Floor("garden", "Garden", null, ""),
            ),
            registry.floors,
        )
    }

    @Test
    fun `orders floors by level, then name, with unlevelled floors last`() {
        assertEquals(listOf("ground_floor", "upstairs", "garden"), registry.floorsOrdered.map { it.id })
    }

    @Test
    fun `parses areas`() {
        val livingRoom = Area(
            id = "living_room",
            name = "Living Room",
            floorId = "ground_floor",
            icon = "mdi:sofa",
            temperatureEntityId = "sensor.living_room_temperature",
            humidityEntityId = null,
            labels = listOf("cosy"),
        )

        assertEquals(livingRoom, registry.area("living_room"))
        assertEquals("sensor.bedroom_humidity", registry.area("bedroom")?.humidityEntityId)
        assertEquals("", registry.area("kitchen")?.icon)
    }

    @Test
    fun `parses devices, preferring the name the user gave`() {
        assertEquals(
            Device("4c1e2b9d", "Reading lamp", "living_room", "Signify Netherlands B.V.", "Hue go", isDisabled = false),
            registry.device("4c1e2b9d"),
        )
        assertEquals("Kitchen plug", registry.device("7a0f3e11")?.name)

        val oldSensor = registry.device("9b22c4aa")
        assertEquals(true, oldSensor?.isDisabled)
        assertEquals("", oldSensor?.manufacturer)
    }

    @Test
    fun `parses compact entity entries`() {
        assertEquals(6, registry.entities.size)

        val power = registry.entry("sensor.kitchen_plug_power")
        assertEquals("Plug power", power?.name)
        assertEquals("shelly", power?.platform)
        assertNull(power?.entityCategory)
        assertEquals(false, power?.isHidden)

        val signal = registry.entry("sensor.kitchen_plug_rssi")
        assertEquals("diagnostic", signal?.entityCategory)
        assertEquals(true, signal?.isHidden)

        assertEquals("config", registry.entry("switch.kitchen_plug_led")?.entityCategory)
        assertEquals(listOf("night"), registry.entry("light.reading_lamp")?.labels)
    }

    @Test
    fun `an entity category index missing from the table gives no category`() {
        assertNull(registry.entry("sensor.mystery")?.entityCategory)
    }

    @Test
    fun `parses full entity_registry list entries too`() {
        val fullListResult = Json.parse(
            """
            [{"entity_id": "sensor.outdoor", "name": null, "area_id": "garden_area", "device_id": "d9",
              "entity_category": "diagnostic", "hidden_by": "integration", "platform": "met", "labels": ["weather"]}]
            """.trimIndent(),
        )

        val entry = Registry.parseEntities(fullListResult).single()

        assertEquals(
            EntityEntry("sensor.outdoor", null, "garden_area", "d9", "diagnostic", true, "met", listOf("weather")),
            entry,
        )
    }

    @Test
    fun `parses labels`() {
        assertEquals(
            listOf(Label("night", "Night", "indigo", "mdi:weather-night"), Label("cosy", "Cosy", "", "")),
            registry.labels,
        )
    }

    @Test
    fun `an entity is in its device's area unless it has its own`() {
        assertEquals("Living Room", registry.areaOf("light.reading_lamp")?.name)
        assertEquals("Bedroom", registry.areaOf("light.bedroom_ceiling")?.name)
        assertEquals("Upstairs", registry.floorOf("light.bedroom_ceiling")?.name)
        assertEquals("Ground floor", registry.floorOf("sensor.kitchen_plug_power")?.name)
        assertEquals("Reading lamp", registry.deviceOf("light.bedroom_ceiling")?.name)
    }

    @Test
    fun `lookups of unknown or null IDs return null`() {
        assertNull(registry.areaOf("light.not_registered"))
        assertNull(registry.areaOf("sensor.mystery"))
        assertNull(registry.floorOf("sensor.mystery"))
        assertNull(registry.area(null))
        assertNull(registry.floor("attic"))
        assertNull(registry.device(null))
    }

    @Test
    fun `missing command results give empty lists`() {
        val onlyAreas = Registry.parse(areasResult, floors = null, devices = null, entities = null)

        assertEquals(3, onlyAreas.areas.size)
        assertEquals(emptyList(), onlyAreas.floors)
        assertEquals(emptyList(), onlyAreas.labels)
        assertFalse(onlyAreas.isEmpty)
        assertTrue(Registry.EMPTY.isEmpty)
    }

    @Test
    fun `a snapshot reads back to an equal registry`() {
        val snapshotText = registry.toJson().toString()

        val restored = Registry.fromJsonText(snapshotText)

        assertEquals(registry, restored)
        assertEquals("diagnostic", restored.entry("sensor.kitchen_plug_rssi")?.entityCategory)
        assertEquals("Living Room", restored.areaOf("light.reading_lamp")?.name)
    }

    @Test
    fun `an unreadable snapshot gives the empty registry`() {
        assertEquals(Registry.EMPTY, Registry.fromJsonText(null))
        assertEquals(Registry.EMPTY, Registry.fromJsonText("{not json"))
    }
}
