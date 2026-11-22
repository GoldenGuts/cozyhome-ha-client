package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.json.Json

/**
 * A Home Assistant WebSocket command without its id
 * ([WebSocket API](https://developers.home-assistant.io/docs/api/websocket)).
 *
 * The session assigns the id when it sends the command, so one command can be reused across connections.
 *
 * @property type the command type, for example `get_states`.
 * @property fields the command's other members, or null when it has none.
 */
class HaCommand(
    val type: String,
    val fields: Json.Obj? = null,
) {
    init {
        // Either would overwrite what the session writes and break matching the result to the command.
        require(fields?.map?.containsKey("id") != true) { "fields must not contain \"id\"" }
        require(fields?.map?.containsKey("type") != true) { "fields must not contain \"type\"" }
    }
}
