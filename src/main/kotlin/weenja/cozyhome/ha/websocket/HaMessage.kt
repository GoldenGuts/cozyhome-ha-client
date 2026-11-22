package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.json.Json

/**
 * One message received from Home Assistant's WebSocket API
 * ([message types](https://developers.home-assistant.io/docs/api/websocket#message-format)).
 *
 * Only the members this client reads are kept; [HaMessages.parse] builds it.
 *
 * @property type the `type` member, or an empty string when it is missing.
 * @property id the command id a `result` or `event` belongs to.
 * @property success the `success` member of a result; false when missing.
 * @property result the `result` member of a successful result.
 * @property errorCode the `error.code` member of a failed result, such as `unknown_command`.
 * @property errorMessage the `error.message` member, or the top-level `message` of `auth_invalid`.
 * @property event the `event` member of an event message.
 * @property haVersion the Home Assistant version sent with `auth_required` and `auth_ok`.
 */
class HaMessage(
    val type: String,
    val id: Int?,
    val success: Boolean,
    val result: Json?,
    val errorCode: String?,
    val errorMessage: String?,
    val event: Json?,
    val haVersion: String?,
) {
    /** The server's greeting: it waits for an `auth` message. */
    val isAuthRequired: Boolean
        get() = type == "auth_required"

    /** The access token was accepted; commands may follow. */
    val isAuthOk: Boolean
        get() = type == "auth_ok"

    /** The access token was rejected; the server closes the connection next. */
    val isAuthInvalid: Boolean
        get() = type == "auth_invalid"

    /** The answer to one command, matched by [id]. */
    val isResult: Boolean
        get() = type == "result"

    /** An event for a subscription, matched by [id]. */
    val isEvent: Boolean
        get() = type == "event"
}
