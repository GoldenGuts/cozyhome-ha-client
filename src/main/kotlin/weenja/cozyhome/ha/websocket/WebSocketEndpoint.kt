package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.normalizeBaseUrl
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException

/**
 * Where to open Home Assistant's WebSocket API for a configured base URL.
 *
 * Users enter the URL they open in a browser, so `http` maps to `ws` and `https` to `wss`
 * ([RFC 6455 section 3](https://www.rfc-editor.org/rfc/rfc6455#section-3)). A base path is
 * kept, because Home Assistant behind a reverse proxy often lives under one
 * (`https://example.com/ha` gives `/ha/api/websocket`).
 *
 * @property host the host name or IP literal to connect to and to verify the certificate for.
 * @property port the explicit port, else 443 for TLS and 80 otherwise.
 * @property isSecure true for `https`, which means TLS (`wss`).
 * @property requestTarget the path for the HTTP upgrade request.
 * @property hostHeader the HTTP `Host` header: the port is included only when the URL names one.
 */
internal class WebSocketEndpoint private constructor(
    val host: String,
    val port: Int,
    val isSecure: Boolean,
    val requestTarget: String,
    val hostHeader: String,
) {
    companion object {
        /** Home Assistant's WebSocket API path, relative to the base URL. */
        private const val API_PATH = "/api/websocket"
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443

        /**
         * Parses a Home Assistant base URL such as `http://homeassistant.local:8123`.
         *
         * @throws MalformedURLException when the URL is not an absolute `http` or `https` URL.
         *   It is an IOException, so a session moves on to the next configured URL.
         */
        fun fromBaseUrl(baseUrl: String): WebSocketEndpoint {
            val uri = parseUri(normalizeBaseUrl(baseUrl))
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw MalformedURLException("Not an http or https URL: $baseUrl")
            }
            val host = uri.host ?: throw MalformedURLException("No host in $baseUrl")
            val isSecure = scheme == "https"
            val hasExplicitPort = uri.port > 0
            val defaultPort = if (isSecure) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            val basePath = uri.rawPath.orEmpty().trimEnd('/')
            return WebSocketEndpoint(
                host = host,
                port = if (hasExplicitPort) uri.port else defaultPort,
                isSecure = isSecure,
                requestTarget = basePath + API_PATH,
                hostHeader = if (hasExplicitPort) "$host:${uri.port}" else host,
            )
        }

        private fun parseUri(text: String): URI {
            try {
                return URI(text)
            } catch (exception: URISyntaxException) {
                throw MalformedURLException("Invalid URL: ${exception.message}")
            }
        }
    }
}
