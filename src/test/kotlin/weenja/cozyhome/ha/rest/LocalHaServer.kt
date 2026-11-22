package weenja.cozyhome.ha.rest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** What the fake Home Assistant saw of one request. */
internal class RecordedRequest(
    val method: String,
    val rawPath: String,
    val rawQuery: String?,
    val authorization: String?,
    val contentType: String?,
    val body: String,
)

/** What the fake Home Assistant answers. */
internal class CannedResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(status: Int, json: String): CannedResponse {
            val headers = mapOf("Content-Type" to "application/json")
            return CannedResponse(status, json.toByteArray(StandardCharsets.UTF_8), headers)
        }
    }
}

/**
 * A fake Home Assistant on the loopback interface, on a free port. Each test decides the answers in [respond] and
 * inspects [requests] afterwards.
 */
internal class LocalHaServer(private val respond: (RecordedRequest) -> CannedResponse) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val recorded = RecordedRequest(
                method = exchange.requestMethod,
                rawPath = exchange.requestURI.rawPath,
                rawQuery = exchange.requestURI.rawQuery,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8),
            )
            requests.add(recorded)
            val response = respond(recorded)
            for ((name, value) in response.headers) {
                exchange.responseHeaders.add(name, value)
            }
            val contentLength = if (response.body.isEmpty()) -1L else response.body.size.toLong()
            exchange.sendResponseHeaders(response.status, contentLength)
            if (response.body.isNotEmpty()) {
                exchange.responseBody.write(response.body)
            }
        }
    }

    companion object {
        /** A loopback URL on a port nothing listens on, so connecting fails at once with "connection refused". */
        fun refusingUrl(): String {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            return "http://127.0.0.1:$port"
        }
    }
}
