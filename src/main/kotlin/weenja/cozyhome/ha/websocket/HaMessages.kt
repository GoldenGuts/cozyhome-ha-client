package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.json.Json

/**
 * Builds and reads Home Assistant [WebSocket API](https://developers.home-assistant.io/docs/api/websocket) messages.
 *
 * No I/O, so the protocol can be tested without a server. Every command here works for a non-admin user.
 */
object HaMessages {
    /**
     * The first message the client sends, answering `auth_required`
     * ([authentication phase](https://developers.home-assistant.io/docs/api/websocket#authentication-phase)).
     */
    fun auth(accessToken: String): String {
        return Json.obj("type" to "auth", "access_token" to accessToken).toString()
    }

    /** A command message: `{"id": id, "type": type, ...fields}`. Ids must increase within a connection. */
    fun command(id: Int, command: HaCommand): String {
        val members = buildMap {
            put("id", Json.Num(id.toDouble()))
            put("type", Json.Str(command.type))
            command.fields?.let { putAll(it.map) }
        }
        return Json.Obj(members).toString()
    }

    /**
     * Parses one incoming WebSocket text message. Home Assistant may coalesce several messages
     * into one JSON array, so the result is a list.
     *
     * @return the messages, or an empty list when [text] is not JSON.
     */
    fun parse(text: String): List<HaMessage> {
        val root = Json.parseOrNull(text) ?: return emptyList()
        val items = if (root is Json.Arr) root.items else listOf(root)
        return items.map { toMessage(it) }
    }

    /**
     * The read-only commands that describe an installation, keyed by a short name: states, registries, the
     * frontend's favourites and suggestions, energy settings and the core config. Run them with [HaSession.tryCall],
     * since older Home Assistant versions lack some of them.
     */
    val scanCommands: Map<String, HaCommand> = linkedMapOf(
        "states" to HaCommand("get_states"),
        "areas" to HaCommand("config/area_registry/list"),
        "floors" to HaCommand("config/floor_registry/list"),
        "labels" to HaCommand("config/label_registry/list"),
        "devices" to HaCommand("config/device_registry/list"),
        "entities" to HaCommand("config/entity_registry/list_for_display"),
        "favorites" to HaCommand("frontend/get_system_data", Json.obj("key" to "home")),
        // Takes no options: Home Assistant rejects extra keys ("limit") with invalid_format.
        "suggested" to HaCommand("usage_prediction/common_control"),
        "energy" to HaCommand("energy/get_prefs"),
        "config" to HaCommand("get_config"),
    )

    /** Events after which the [scanCommands] answers are out of date. */
    val scanInvalidatingEvents: List<String> = listOf(
        "area_registry_updated",
        "floor_registry_updated",
        "device_registry_updated",
        "entity_registry_updated",
        "label_registry_updated",
        "lovelace_updated",
    )

    /**
     * Subscribes to one event type
     * ([subscribe_events](https://developers.home-assistant.io/docs/api/websocket#subscribe-to-events)).
     * Events then arrive with the id of this command.
     */
    fun subscribeEvents(eventType: String): HaCommand {
        return HaCommand("subscribe_events", Json.obj("event_type" to eventType))
    }

    /**
     * Calls a service, such as `light.turn_on`
     * ([call_service](https://developers.home-assistant.io/docs/api/websocket#calling-a-service-action)).
     *
     * @param data the service data; null sends an empty object.
     * @param returnResponse asks for the service's response data. Services that return none fail the call when
     *   this is true.
     */
    fun callService(
        domain: String,
        service: String,
        data: Json.Obj? = null,
        returnResponse: Boolean = false,
    ): HaCommand {
        val fields = buildMap {
            put("domain", Json.Str(domain))
            put("service", Json.Str(service))
            put("service_data", data ?: Json.Obj(emptyMap()))
            if (returnResponse) {
                put("return_response", Json.Bool(true))
            }
        }
        return HaCommand("call_service", Json.Obj(fields))
    }

    /**
     * How much a long-term statistic changed since the start of today, in the server's time
     * zone. Read the answer with [changeOf].
     */
    fun statisticChangeToday(statisticId: String): HaCommand {
        val fields = Json.obj(
            "statistic_id" to statisticId,
            "calendar" to Json.obj("period" to "day"),
            "types" to listOf("change"),
        )
        return HaCommand("recorder/statistic_during_period", fields)
    }

    /**
     * Entity ids in a `frontend/get_system_data` answer for the key `home`.
     *
     * The frontend owns that document and its layout has changed between releases, so every
     * `favorite_entities` or `favorites` list is collected wherever it sits.
     */
    fun favoritesOf(result: Json?): List<String> {
        if (result == null) {
            return emptyList()
        }
        val found = ArrayList<String>()
        collectFavorites(result, found)
        return found.filter { isEntityId(it) }.distinct()
    }

    /** Entity ids in a `usage_prediction/common_control` answer (`{"entities": [...]}`). */
    fun suggestedOf(result: Json?): List<String> {
        if (result == null) {
            return emptyList()
        }
        val items = when {
            result is Json.Arr -> result.items
            result.arr("entities").isNotEmpty() -> result.arr("entities")
            else -> result.arr("entity_ids")
        }
        return items.mapNotNull { entityIdOf(it) }.filter { isEntityId(it) }.distinct()
    }

    /** The `change` of a `recorder/statistic_during_period` answer (`{"change": 12.3}`). */
    fun changeOf(result: Json?): Double? {
        return result?.num("change")
    }

    private fun toMessage(item: Json): HaMessage {
        val error = item["error"]
        return HaMessage(
            type = item.str("type").orEmpty(),
            id = item.num("id")?.toInt(),
            success = item.flag("success") == true,
            result = item["result"],
            errorCode = error?.str("code"),
            errorMessage = error?.str("message") ?: item.str("message"),
            event = item["event"],
            haVersion = item.str("ha_version"),
        )
    }

    private fun collectFavorites(node: Json, found: MutableList<String>) {
        when (node) {
            is Json.Obj -> {
                for ((key, value) in node.map) {
                    if (key == "favorite_entities" || key == "favorites") {
                        found.addAll(value.list.mapNotNull { entityIdOf(it) })
                    } else {
                        collectFavorites(value, found)
                    }
                }
            }
            is Json.Arr -> node.items.forEach { collectFavorites(it, found) }
            else -> Unit
        }
    }

    /** Lists hold either bare entity ids or objects with an `entity_id` member. */
    private fun entityIdOf(item: Json): String? {
        return item.string ?: item.str("entity_id")
    }

    /** Entity ids are `domain.object_id`; anything without a dot is not one. */
    private fun isEntityId(text: String): Boolean {
        return text.contains('.')
    }
}
