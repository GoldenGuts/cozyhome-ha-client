package weenja.cozyhome.ha.model

import weenja.cozyhome.ha.json.Json
import kotlin.math.roundToInt

/**
 * The current state of one entity, as Home Assistant sends it in a
 * [state object](https://www.home-assistant.io/docs/configuration/state_object/): from the REST
 * endpoint `GET /api/states`, the WebSocket command `get_states`, and `state_changed` events.
 *
 * Home Assistant sends every state as a string (`"on"`, `"21.5"`, `"unavailable"`) and puts
 * everything else in free-form [attributes] whose shape depends on the integration. The helpers here
 * read the attributes that most domains share; [attr] and friends reach the rest.
 *
 * @property id the entity ID, `<domain>.<object_id>`, for example `light.kitchen`.
 * @property state the state text exactly as sent.
 * @property attributes the attributes object; an empty object when Home Assistant sent none.
 * @property lastChanged ISO 8601 time the state text last changed, or empty when unknown.
 * @property lastUpdated ISO 8601 time the state or an attribute last changed, or empty when unknown.
 */
data class EntityState(
    val id: String,
    val state: String,
    val attributes: Json,
    val lastChanged: String = "",
    val lastUpdated: String = "",
) {

    /** The part of [id] before the dot, such as `light` or `sensor`. It decides which services apply. */
    val domain: String
        get() = id.substringBefore('.')

    /** The part of [id] after the dot. */
    val objectId: String
        get() = id.substringAfter('.')

    /**
     * The name to show: the `friendly_name` attribute, or, when that is missing or blank, the object ID
     * made readable (`kitchen_light` becomes `Kitchen light`), which is close to what Home Assistant's
     * own frontend falls back to.
     */
    val name: String
        get() {
            val friendlyName = attributes.str("friendly_name")
            if (!friendlyName.isNullOrBlank()) {
                return friendlyName
            }
            return objectId.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /** The `device_class` attribute (`temperature`, `door`, ...), or empty. */
    val deviceClass: String
        get() = attributes.str("device_class") ?: ""

    /** The `unit_of_measurement` attribute (`°C`, `%`, ...), or empty. */
    val unit: String
        get() = attributes.str("unit_of_measurement") ?: ""

    /** The `icon` attribute as a Material Design Icons name (`mdi:lamp`), or empty. */
    val icon: String
        get() = attributes.str("icon") ?: ""

    /**
     * False when Home Assistant reports `unavailable` (the device cannot be reached) or `unknown`
     * (no value yet). Both are special states shared by every domain and must not be shown as a real
     * value. See [generic properties](https://developers.home-assistant.io/docs/core/entity/#generic-properties).
     */
    val isAvailable: Boolean
        get() = state != STATE_UNAVAILABLE && state != STATE_UNKNOWN

    /** True when the state is `on`, as for lights, switches, fans and binary sensors. */
    val isOn: Boolean
        get() = state == STATE_ON

    /** The state as a number, for sensors; null when the state is not numeric. */
    val number: Double?
        get() = state.toDoubleOrNull()

    /**
     * The brightness of a light that is on, from 0 to 100 percent; null when it is off or has no
     * brightness. Home Assistant reports
     * [brightness](https://www.home-assistant.io/integrations/light/) on a 0 to 255 scale.
     */
    val brightnessPercent: Int?
        get() = attrDouble("brightness")?.let { brightness -> (brightness / BRIGHTNESS_PER_PERCENT).roundToInt() }

    /** The attribute [key] as JSON, or null when it is missing. */
    fun attr(key: String): Json? = attributes[key]

    /** The attribute [key] as text, or null. See [Json.string]. */
    fun attrString(key: String): String? = attributes.str(key)

    /** The attribute [key] as a number, or null. See [Json.double]. */
    fun attrDouble(key: String): Double? = attributes.num(key)

    /** The attribute [key] as a boolean, or null. See [Json.bool]. */
    fun attrBool(key: String): Boolean? = attributes.flag(key)

    /** This state in Home Assistant's own shape, so [from] reads it back (for example from a cache). */
    fun toJson(): Json {
        return Json.obj(
            "entity_id" to id,
            "state" to state,
            "attributes" to attributes,
            "last_changed" to lastChanged,
            "last_updated" to lastUpdated,
        )
    }

    companion object {
        private const val STATE_ON = "on"
        private const val STATE_UNAVAILABLE = "unavailable"
        private const val STATE_UNKNOWN = "unknown"
        private const val BRIGHTNESS_PER_PERCENT = 255.0 / 100.0

        /**
         * Reads one state object. Returns null when it has no usable `entity_id` (missing, or without
         * the dot every entity ID has), so one malformed entry cannot break a whole list.
         * A missing `state` is read as `unknown`, matching what Home Assistant shows for it.
         */
        fun from(json: Json): EntityState? {
            val id = json.str("entity_id") ?: return null
            if (!id.contains('.')) {
                return null
            }
            return EntityState(
                id = id,
                state = json.str("state") ?: STATE_UNKNOWN,
                attributes = json["attributes"] ?: Json.Obj(emptyMap()),
                lastChanged = json.str("last_changed") ?: "",
                lastUpdated = json.str("last_updated") ?: "",
            )
        }

        /** Reads the array that `GET /api/states` and `get_states` return, skipping malformed entries. */
        fun parseList(json: Json): List<EntityState> = json.list.mapNotNull { from(it) }

        /**
         * Parses [text] and reads it like [parseList].
         *
         * @throws weenja.cozyhome.ha.json.JsonException when [text] is not valid JSON.
         */
        fun parseList(text: String): List<EntityState> = parseList(Json.parse(text))
    }
}
