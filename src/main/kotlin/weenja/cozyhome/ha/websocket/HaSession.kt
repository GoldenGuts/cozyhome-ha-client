package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.HTTP_UNAUTHORIZED
import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.auth.AccessTokenProvider
import weenja.cozyhome.ha.firstReachable
import weenja.cozyhome.ha.json.Json
import weenja.cozyhome.ha.normalizeBaseUrl
import java.io.Closeable
import java.io.IOException

/**
 * One authenticated connection to Home Assistant's
 * [WebSocket API](https://developers.home-assistant.io/docs/api/websocket).
 *
 * Commands run one after another: [call] sends a command and reads until its result arrives.
 * Events for subscriptions that arrive in the meantime are queued for [nextEvent].
 *
 * All calls block. A session is not thread-safe; use it from one thread at a time.
 *
 * @property haVersion the Home Assistant version the server announced, when it did.
 */
class HaSession private constructor(
    private val connection: WebSocketConnection,
    val haVersion: String?,
) : Closeable {
    private var nextCommandId = 1
    private val pendingEvents = ArrayDeque<Json>()

    /**
     * Sends [command] and blocks until its result arrives.
     *
     * @return the `result` member of the answer, or null when it has none.
     * @throws HaException when Home Assistant answers with an error.
     * @throws IOException when the connection fails or closes first.
     */
    fun call(command: HaCommand): Json? {
        val commandId = nextCommandId++
        connection.send(HaMessages.command(commandId, command))
        while (true) {
            val text = connection.receive() ?: throw IOException("Home Assistant closed the connection")
            for (message in HaMessages.parse(text)) {
                if (message.isEvent) {
                    message.event?.let { pendingEvents.addLast(it) }
                } else if (message.isResult && message.id == commandId) {
                    return resultOf(command, message)
                }
            }
        }
    }

    /**
     * Like [call], but returns null when Home Assistant answers with an error, such as `unknown_command` from a
     * version that predates the command. Network errors are still thrown.
     */
    fun tryCall(command: HaCommand): Json? {
        return try {
            call(command)
        } catch (_: HaException) {
            null
        }
    }

    /**
     * Blocks until the next subscription event and returns its `event` member.
     *
     * @return the event, or null once the connection closed.
     * @throws IOException on a read timeout or a broken connection.
     */
    fun nextEvent(): Json? {
        while (pendingEvents.isEmpty()) {
            val text = connection.receive() ?: return null
            for (message in HaMessages.parse(text)) {
                if (message.isEvent) {
                    message.event?.let { pendingEvents.addLast(it) }
                }
            }
        }
        return pendingEvents.removeFirst()
    }

    override fun close() {
        connection.close()
    }

    private fun resultOf(command: HaCommand, message: HaMessage): Json? {
        if (!message.success) {
            val detail = listOfNotNull(message.errorCode, message.errorMessage).joinToString(" ")
            throw HaException("${command.type}: $detail".trim())
        }
        return message.result
    }

    companion object {
        /**
         * Connects to the first reachable URL in [baseUrls], authenticates, runs [block] and closes the session.
         *
         * A network error moves on to the next URL. A rejected login stops at once, since every URL reaches the same
         * server. Errors thrown by [block] are not retried.
         *
         * @param readTimeoutMillis how long a read may block; 0 waits forever, for sessions that only listen for
         *   events.
         * @throws HaException when Home Assistant rejects the access token.
         * @throws IOException the last network error when no URL could be reached.
         */
        fun <T> open(
            baseUrls: List<String>,
            tokenProvider: AccessTokenProvider,
            connectTimeoutMillis: Int = WebSocketConnection.DEFAULT_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis: Int = WebSocketConnection.DEFAULT_READ_TIMEOUT_MILLIS,
            block: (HaSession) -> T,
        ): T {
            val session = firstReachable(baseUrls) { baseUrl ->
                connect(baseUrl, tokenProvider, connectTimeoutMillis, readTimeoutMillis)
            }
            return session.use(block)
        }

        /**
         * Connects to one URL and runs the
         * [authentication phase](https://developers.home-assistant.io/docs/api/websocket#authentication-phase).
         * The caller owns the returned session and must close it.
         *
         * @throws HaException when Home Assistant rejects the token.
         * @throws IOException when the server cannot be reached or closes during the exchange.
         */
        fun connect(
            baseUrl: String,
            tokenProvider: AccessTokenProvider,
            connectTimeoutMillis: Int = WebSocketConnection.DEFAULT_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis: Int = WebSocketConnection.DEFAULT_READ_TIMEOUT_MILLIS,
        ): HaSession {
            val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
            val connection = WebSocketConnection.connect(normalizedBaseUrl, connectTimeoutMillis, readTimeoutMillis)
            try {
                return authenticate(connection, normalizedBaseUrl, tokenProvider)
            } catch (exception: Exception) {
                // The token provider may throw anything, not only IOException.
                connection.close()
                throw exception
            }
        }

        private fun authenticate(
            connection: WebSocketConnection,
            baseUrl: String,
            tokenProvider: AccessTokenProvider,
        ): HaSession {
            var announcedVersion: String? = null
            while (true) {
                val text = connection.receive() ?: throw IOException("Home Assistant closed the connection during auth")
                val message = HaMessages.parse(text).firstOrNull() ?: continue
                when {
                    message.isAuthRequired -> {
                        announcedVersion = message.haVersion
                        val accessToken = tokenProvider.accessToken(baseUrl, forceRefresh = false)
                        connection.send(HaMessages.auth(accessToken))
                    }
                    message.isAuthOk -> return HaSession(connection, message.haVersion ?: announcedVersion)
                    message.isAuthInvalid -> {
                        val reason = message.errorMessage.orEmpty()
                        throw HaException("Home Assistant rejected the login: $reason".trim(), HTTP_UNAUTHORIZED)
                    }
                }
            }
        }
    }
}
