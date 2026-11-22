package weenja.cozyhome.ha.model

/**
 * A [floor](https://www.home-assistant.io/docs/organizing/floors/), from the WebSocket command
 * `config/floor_registry/list`.
 *
 * @property id the `floor_id`.
 * @property name the display name; the ID when Home Assistant sent none.
 * @property level the storey number (0 for the ground floor, negative for basements), or null when unset.
 * @property icon a Material Design Icons name (`mdi:home-floor-1`), or empty.
 */
data class Floor(
    val id: String,
    val name: String,
    val level: Int?,
    val icon: String,
)

/**
 * An [area](https://www.home-assistant.io/docs/organizing/areas/) (a room or zone), from the WebSocket
 * command `config/area_registry/list`.
 *
 * @property id the `area_id`.
 * @property name the display name; the ID when Home Assistant sent none.
 * @property floorId the floor the area is on, or null.
 * @property icon a Material Design Icons name, or empty.
 * @property temperatureEntityId the sensor the user picked as the area's temperature, or null.
 * @property humidityEntityId the sensor the user picked as the area's humidity, or null.
 * @property labels the IDs of the area's [Label]s.
 */
data class Area(
    val id: String,
    val name: String,
    val floorId: String?,
    val icon: String,
    val temperatureEntityId: String?,
    val humidityEntityId: String?,
    val labels: List<String>,
)

/**
 * A device from the [device registry](https://developers.home-assistant.io/docs/device_registry_index/),
 * as the WebSocket command `config/device_registry/list` returns it.
 *
 * @property id the device ID.
 * @property name the name the user gave the device, else the integration's name for it, else empty.
 * @property areaId the device's area, or null. Its entities inherit it unless they set their own.
 * @property manufacturer the manufacturer, or empty.
 * @property model the model, or empty.
 * @property isDisabled true when `disabled_by` is set (by the user, an integration or a config entry).
 */
data class Device(
    val id: String,
    val name: String,
    val areaId: String?,
    val manufacturer: String,
    val model: String,
    val isDisabled: Boolean,
)

/**
 * One entry of the [entity registry](https://developers.home-assistant.io/docs/entity_registry_index/).
 *
 * The client reads it from `config/entity_registry/list_for_display`, which the Home Assistant frontend
 * uses too: it is much smaller than `config/entity_registry/list` on large installations because it
 * sends short keys and leaves out default values. See [Registry.parseEntities] for the format.
 *
 * @property entityId the entity ID, such as `light.kitchen`.
 * @property name the name the user gave the entity, or null to use its state's `friendly_name`.
 * @property areaId the entity's own area, or null to use its device's area.
 * @property deviceId the device the entity belongs to, or null.
 * @property entityCategory `config` or `diagnostic` for entities that are not the device's main function,
 *   else null. See [registry properties](https://developers.home-assistant.io/docs/core/entity/#registry-properties).
 * @property isHidden true when the user or an integration hid the entity.
 * @property platform the integration that provides the entity, such as `hue` or `mobile_app`, or null.
 * @property labels the IDs of the entity's [Label]s.
 */
data class EntityEntry(
    val entityId: String,
    val name: String?,
    val areaId: String?,
    val deviceId: String?,
    val entityCategory: String?,
    val isHidden: Boolean,
    val platform: String?,
    val labels: List<String>,
)

/**
 * A [label](https://www.home-assistant.io/docs/organizing/labels/), from the WebSocket command
 * `config/label_registry/list`.
 *
 * @property id the `label_id`.
 * @property name the display name; the ID when Home Assistant sent none.
 * @property color a colour name or hex value as the user chose it, or empty.
 * @property icon a Material Design Icons name, or empty.
 */
data class Label(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
)
