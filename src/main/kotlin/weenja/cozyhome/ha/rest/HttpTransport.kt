package weenja.cozyhome.ha.rest

import weenja.cozyhome.ha.HaException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One HTTP request to Home Assistant.
 *
 * Not a data class: a generated `toString()` would print the bearer token into logs and crash reports.
 */
internal class HttpRequest(
    val url: String,
    val method: String,
    val bearerToken: String?,
    val body: ByteArray?,
    val contentType: String?,
) {
    override fun toString(): String = "HttpRequest($method $url)"

    companion object {
        fun get(url: String, bearerToken: String?): HttpRequest {
            return HttpRequest(url, "GET", bearerToken, body = null, contentType = null)
        }

        fun postJson(url: String, bearerToken: String?, json: String): HttpRequest {
            val body = json.toByteArray(StandardCharsets.UTF_8)
            return HttpRequest(url, "POST", bearerToken, body, "application/json")
        }

        /** An `application/x-www-form-urlencoded` POST, the format Home Assistant's token endpoint expects. */
        fun postForm(url: String, fields: Map<String, String>): HttpRequest {
            val encodedFields = fields.entries.joinToString("&") { (name, value) ->
                encodeQueryValue(name) + "=" + encodeQueryValue(value)
            }
            val body = encodedFields.toByteArray(StandardCharsets.UTF_8)
            return HttpRequest(url, "POST", bearerToken = null, body, "application/x-www-form-urlencoded")
        }
    }
}

/** Status and raw body of an HTTP response; the body of an error response is kept too. */
internal class HttpResponse(val status: Int, val body: ByteArray) {
    val isSuccessful: Boolean
        get() = status in 200..299

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * Blocking HTTP on [HttpURLConnection], to avoid an HTTP client dependency.
 *
 * Network failures surface as [IOException]; every HTTP status, including errors, comes back as an [HttpResponse].
 */
internal object HttpTransport {
    /** Short, so a dead primary URL fails over to the fallback quickly. */
    const val CONNECT_TIMEOUT_MILLIS: Int = 5_000

    /** History and calendar queries on a slow Raspberry Pi can take several seconds. */
    const val READ_TIMEOUT_MILLIS: Int = 15_000

    /** The same cap as for WebSocket messages, so a broken server cannot exhaust memory. */
    const val MAX_RESPONSE_BYTES: Int = 64 * 1024 * 1024

    private const val ACCEPT = "application/json, image/*"

    fun execute(request: HttpRequest): HttpResponse {
        val connection = openConnection(request.url)
        try {
            configure(connection, request)
            writeBody(connection, request)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readLimited(it, status) } ?: ByteArray(0)
            return HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val parsedUrl = try {
            URI(url).toURL()
        } catch (malformed: URISyntaxException) {
            throw MalformedURLException("Malformed URL: ${malformed.reason}")
        } catch (_: IllegalArgumentException) {
            throw MalformedURLException("Not an absolute URL")
        }
        return parsedUrl.openConnection() as? HttpURLConnection ?: throw IOException("Not an http or https URL")
    }

    private fun configure(connection: HttpURLConnection, request: HttpRequest) {
        connection.requestMethod = request.method
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        // Home Assistant does not redirect its API, and following a redirect could carry the token to another host.
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", ACCEPT)
        request.bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    }

    private fun writeBody(connection: HttpURLConnection, request: HttpRequest) {
        val body = request.body ?: return
        connection.doOutput = true
        request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
    }

    private fun readLimited(input: InputStream, status: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                return output.toByteArray()
            }
            if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                throw HaException("Home Assistant sent more than $MAX_RESPONSE_BYTES bytes", status)
            }
            output.write(buffer, 0, count)
        }
    }
}

/** Encodes a query parameter name or value as UTF-8 percent-encoding. */
internal fun encodeQueryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/**
 * Encodes one path segment. [URLEncoder] targets form bodies and writes a space as `+`,
 * which a path would keep as a literal plus sign, so spaces become `%20` instead.
 */
internal fun encodePathSegment(value: String): String = encodeQueryValue(value).replace("+", "%20")
