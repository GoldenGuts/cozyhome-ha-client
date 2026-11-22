package weenja.cozyhome.ha.model

import weenja.cozyhome.ha.json.Json

/**
 * How one Home Assistant installation is organised: its floors, areas, devices, labels and entity
 * registry entries. It answers questions a state alone cannot, such as "which room is this sensor in?".
 *
 * Each list comes from its own WebSocket command (see the entry types). Build a registry from the
 * answers with [parse]; store and restore one with [toJson] and [fromJson].
 */
data class Registry(
    val areas: List<Area>,
    val floors: List<Floor>,
    val devices: List<Device>,
    val entities: List<EntityEntry>,
    val labels: List<Label> = emptyList(),
) {
    private val areaById: Map<String, Area> = areas.associateBy { it.id }
    private val floorById: Map<String, Floor> = floors.associateBy { it.id }
    private val deviceById: Map<String, Device> = devices.associateBy { it.id }
    private val entryByEntityId: Map<String, EntityEntry> = entities.associateBy { it.entityId }

    /**
     * True when there is nothing to organise by: no areas, devices or entity entries.
     * Floors and labels are not checked because they are only meaningful together with areas and entities.
     */
    val isEmpty: Boolean
        get() = areas.isEmpty() && devices.isEmpty() && entities.isEmpty()

    /** Floors ordered by [Floor.level], then by name; floors without a level come last. */
    val floorsOrdered: List<Floor>
        get() = floors.sortedWith(compareBy({ it.level ?: Int.MAX_VALUE }, { it.name.lowercase() }))

    /** The area with [id], or null when [id] is null or unknown. */
    fun area(id: String?): Area? = id?.let { areaById[it] }

    /** The floor with [id], or null when [id] is null or unknown. */
    fun floor(id: String?): Floor? = id?.let { floorById[it] }

    /** The device with [id], or null when [id] is null or unknown. */
    fun device(id: String?): Device? = id?.let { deviceById[it] }

    /** The registry entry of [entityId], or null when the entity is not in the registry (as for YAML-only ones). */
    fun entry(entityId: String): EntityEntry? = entryByEntityId[entityId]

    /**
     * The area [entityId] is in: its own area when it has one, else its device's area. This is the same
     * rule Home Assistant applies, so an entity follows its device unless the user moved it.
     */
    fun areaOf(entityId: String): Area? {
        val entry = entryByEntityId[entityId] ?: return null
        return area(entry.areaId) ?: area(device(entry.deviceId)?.areaId)
    }

    /** The floor of the area [entityId] is in (see [areaOf]), or null. */
    fun floorOf(entityId: String): Floor? = floor(areaOf(entityId)?.floorId)

    /** The device [entityId] belongs to, or null. */
    fun deviceOf(entityId: String): Device? = device(entryByEntityId[entityId]?.deviceId)

    /**
     * This registry as JSON in the same shapes Home Assistant sends, under the keys `areas`, `floors`,
     * `devices`, `entities` and `labels`. Reusing those shapes means [fromJson] needs no second parser.
     */
    fun toJson(): Json {
        return Json.obj(
            "areas" to areas.map { areaToJson(it) },
            "floors" to floors.map { floorToJson(it) },
            "devices" to devices.map { deviceToJson(it) },
            "entities" to entitiesToDisplayJson(entities),
            "labels" to labels.map { labelToJson(it) },
        )
    }

    /** Parsers for the registry commands' answers. Each one skips entries without an ID. */
    companion object {

        /** A registry with nothing in it, for an installation that has not been read yet. */
        val EMPTY: Registry = Registry(emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * The category table `list_for_display` would send. The snapshot always writes it, so its
         * indices stay stable whatever the source installation sent.
         */
        private val SNAPSHOT_CATEGORIES: List<String> = listOf("config", "diagnostic")

        /**
         * Builds a registry from the results of the registry commands. A null argument (a command that
         * failed or that an older Home Assistant does not know, like floors and labels before 2024.4)
         * gives an empty list.
         */
        fun parse(
            areas: Json?,
            floors: Json?,
            devices: Json?,
            entities: Json?,
            labels: Json? = null,
        ): Registry {
            return Registry(
                areas = areas?.let { parseAreas(it) } ?: emptyList(),
                floors = floors?.let { parseFloors(it) } ?: emptyList(),
                devices = devices?.let { parseDevices(it) } ?: emptyList(),
                entities = entities?.let { parseEntities(it) } ?: emptyList(),
                labels = labels?.let { parseLabels(it) } ?: emptyList(),
            )
        }

        /** Reads a registry written by [toJson]; [EMPTY] when [json] is null. */
        fun fromJson(json: Json?): Registry {
            if (json == null) {
                return EMPTY
            }
            return parse(json["areas"], json["floors"], json["devices"], json["entities"], json["labels"])
        }

        /** Parses [text] and reads it like [fromJson]; [EMPTY] when it is null or not valid JSON. */
        fun fromJsonText(text: String?): Registry = fromJson(Json.parseOrNull(text))

        /** Reads the answer of `config/area_registry/list`. */
        fun parseAreas(json: Json): List<Area> = json.list.mapNotNull { parseArea(it) }

        /** Reads the answer of `config/floor_registry/list`. */
        fun parseFloors(json: Json): List<Floor> = json.list.mapNotNull { parseFloor(it) }

        /** Reads the answer of `config/device_registry/list`. */
        fun parseDevices(json: Json): List<Device> = json.list.mapNotNull { parseDevice(it) }

        /** Reads the answer of `config/label_registry/list`. */
        fun parseLabels(json: Json): List<Label> = json.list.mapNotNull { parseLabel(it) }

        /**
         * Reads the answer of `config/entity_registry/list_for_display`:
         *
         * ```json
         * {"entity_categories": {"0": "config", "1": "diagnostic"},
         *  "entities": [{"ei": "sensor.plug_power", "di": "d1", "ec": 1, "lb": ["energy"]}]}
         * ```
         *
         * The short keys are `ei` entity ID, `en` name, `ai` area, `di` device, `ec` entity category
         * (an index into `entity_categories`), `hb` hidden, `pl` platform and `lb` labels.
         * A plain array of full `config/entity_registry/list` entries is read as well, so either
         * command's answer works.
         */
        fun parseEntities(json: Json): List<EntityEntry> {
            val categoryNames = json["entity_categories"]?.obj ?: emptyMap()
            val entries = if (json is Json.Arr) json.list else json.arr("entities")
            return entries.mapNotNull { parseEntityEntry(it, categoryNames) }
        }

        private fun parseArea(json: Json): Area? {
            val id = json.str("area_id") ?: return null
            return Area(
                id = id,
                name = json.str("name") ?: id,
                floorId = json.str("floor_id"),
                icon = json.str("icon") ?: "",
                temperatureEntityId = json.str("temperature_entity_id"),
                humidityEntityId = json.str("humidity_entity_id"),
                labels = json.strings("labels"),
            )
        }

        private fun parseFloor(json: Json): Floor? {
            val id = json.str("floor_id") ?: return null
            return Floor(
                id = id,
                name = json.str("name") ?: id,
                level = json.num("level")?.toInt(),
                icon = json.str("icon") ?: "",
            )
        }

        private fun parseDevice(json: Json): Device? {
            val id = json.str("id") ?: return null
            return Device(
                id = id,
                name = json.str("name_by_user") ?: json.str("name") ?: "",
                areaId = json.str("area_id"),
                manufacturer = json.str("manufacturer") ?: "",
                model = json.str("model") ?: "",
                isDisabled = json.str("disabled_by") != null,
            )
        }

        private fun parseLabel(json: Json): Label? {
            val id = json.str("label_id") ?: return null
            return Label(
                id = id,
                name = json.str("name") ?: id,
                color = json.str("color") ?: "",
                icon = json.str("icon") ?: "",
            )
        }

        /** Reads one entry in either the short `list_for_display` keys or the full `list` keys. */
        private fun parseEntityEntry(json: Json, categoryNames: Map<String, Json>): EntityEntry? {
            val entityId = json.str("ei") ?: json.str("entity_id") ?: return null
            return EntityEntry(
                entityId = entityId,
                name = json.str("en") ?: json.str("name"),
                areaId = json.str("ai") ?: json.str("area_id"),
                deviceId = json.str("di") ?: json.str("device_id"),
                entityCategory = parseEntityCategory(json, categoryNames),
                isHidden = json.flag("hb") == true || json.str("hidden_by") != null,
                platform = json.str("pl") ?: json.str("platform"),
                labels = json.strings("lb").ifEmpty { json.strings("labels") },
            )
        }

        /**
         * `list_for_display` sends the category as a number that indexes its `entity_categories`
         * table; an index missing from the table gives null rather than a meaningless number.
         * The full `list` command sends the name itself.
         */
        private fun parseEntityCategory(json: Json, categoryNames: Map<String, Json>): String? {
            val shortCategory = json["ec"] ?: return json.str("entity_category")
            if (shortCategory is Json.Num) {
                val index = shortCategory.string ?: return null
                return categoryNames[index]?.string
            }
            return shortCategory.string
        }

        private fun areaToJson(area: Area): Json {
            return Json.obj(
                "area_id" to area.id,
                "name" to area.name,
                "floor_id" to area.floorId,
                "icon" to area.icon,
                "temperature_entity_id" to area.temperatureEntityId,
                "humidity_entity_id" to area.humidityEntityId,
                "labels" to area.labels,
            )
        }

        private fun floorToJson(floor: Floor): Json {
            return Json.obj(
                "floor_id" to floor.id,
                "name" to floor.name,
                "level" to floor.level,
                "icon" to floor.icon,
            )
        }

        /**
         * The device registry says who disabled a device; only the fact that it is disabled is kept, so
         * the snapshot writes `user` for every disabled device.
         */
        private fun deviceToJson(device: Device): Json {
            return Json.obj(
                "id" to device.id,
                "name" to device.name,
                "area_id" to device.areaId,
                "manufacturer" to device.manufacturer,
                "model" to device.model,
                "disabled_by" to if (device.isDisabled) "user" else null,
            )
        }

        private fun labelToJson(label: Label): Json {
            return Json.obj(
                "label_id" to label.id,
                "name" to label.name,
                "color" to label.color,
                "icon" to label.icon,
            )
        }

        private fun entitiesToDisplayJson(entries: List<EntityEntry>): Json {
            val categoryTable = SNAPSHOT_CATEGORIES.withIndex().associate { (index, name) -> index.toString() to name }
            return Json.obj(
                "entity_categories" to categoryTable,
                "entities" to entries.map { entityEntryToDisplayJson(it) },
            )
        }

        /** Writes the short keys. Unset values are written as null, which reads back the same as a missing key. */
        private fun entityEntryToDisplayJson(entry: EntityEntry): Json {
            val categoryIndex = SNAPSHOT_CATEGORIES.indexOf(entry.entityCategory).takeIf { it >= 0 }
            return Json.obj(
                "ei" to entry.entityId,
                "en" to entry.name,
                "ai" to entry.areaId,
                "di" to entry.deviceId,
                "ec" to categoryIndex,
                "hb" to if (entry.isHidden) true else null,
                "pl" to entry.platform,
                "lb" to entry.labels,
            )
        }
    }
}
