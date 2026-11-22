package weenja.cozyhome.ha.rest

import weenja.cozyhome.ha.HTTP_UNAUTHORIZED
import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.auth.AccessTokenProvider
import weenja.cozyhome.ha.auth.TokenStore
import weenja.cozyhome.ha.firstReachable
import weenja.cozyhome.ha.json.Json
import weenja.cozyhome.ha.normalizeBaseUrl
import java.io.IOException
import java.net.URI

/**
 * A few endpoints of Home Assistant's [REST API](https://developers.home-assistant.io/docs/api/rest/).
 *
 * Every call blocks. Each one goes to [TokenStore.baseUrl] first and, only if that URL cannot be reached at all,
 * to [TokenStore.fallbackUrl]. An HTTP error raises [HaException] without trying the fallback, since both URLs lead
 * to the same instance.
 *
 * A 401 is retried once with a refreshed OAuth token, because an access token can be revoked early (after a server
 * restart, for example). Long-lived tokens cannot be refreshed, so they are not retried.
 *
 * Redirects are never followed, so the bearer token cannot be forwarded to another host.
 *
 * @param tokenProvider usually a [weenja.cozyhome.ha.auth.TokenManager], shared with
 *   [weenja.cozyhome.ha.websocket.HaSession].
 */
class HaRestClient(
    private val tokenStore: TokenStore,
    private val tokenProvider: AccessTokenProvider,
) {
    /**
     * All entity states (`GET /api/states`), as an array of state objects.
     *
     * @throws HaException when Home Assistant answers with an error.
     * @throws IOException when no configured URL can be reached.
     */
    fun states(): Json {
        return expectJson(get("/api/states"), "states")
    }

    /**
     * One entity's state (`GET /api/states/<entity_id>`), or null when Home Assistant does not know the entity.
     *
     * @throws HaException when Home Assistant answers with another error.
     * @throws IOException when no configured URL can be reached.
     */
    fun state(entityId: String): Json? {
        val response = get("/api/states/" + encodePathSegment(entityId))
        if (response.status == HTTP_NOT_FOUND) {
            return null
        }
        return expectJson(response, "state of $entityId")
    }

    /**
     * Calls a service (`POST /api/services/<domain>/<service>`), for example `light.turn_on`.
     *
     * @param data the service data, such as `{"entity_id": "light.kitchen"}`; null sends an empty object.
     * @throws HaException when Home Assistant rejects the call (an unknown service or invalid data gives 400).
     * @throws IOException when no configured URL can be reached.
     */
    fun callService(domain: String, service: String, data: Json.Obj? = null) {
        val response = post(servicePath(domain, service), data)
        expectSuccess(response, "$domain.$service")
    }

    /**
     * Calls a service that returns data, such as `todo.get_items` or `weather.get_forecasts`, and returns the
     * `service_response` member of the answer, or null when there is none.
     *
     * @throws HaException when Home Assistant rejects the call.
     * @throws IOException when no configured URL can be reached.
     */
    fun callServiceWithResponse(domain: String, service: String, data: Json.Obj? = null): Json? {
        val response = post(servicePath(domain, service) + "?return_response", data)
        return expectJson(response, "$domain.$service")["service_response"]
    }

    /**
     * Events of one calendar entity between two times (`GET /api/calendars/<entity_id>`), as an array.
     *
     * @param startIso the start as an ISO 8601 date-time, for example `2026-11-22T00:00:00+01:00`.
     * @param endIso the end, in the same format.
     * @throws HaException when Home Assistant answers with an error.
     * @throws IOException when no configured URL can be reached.
     */
    fun calendarEvents(entityId: String, startIso: String, endIso: String): Json {
        val path = "/api/calendars/" + encodePathSegment(entityId) +
            "?start=" + encodeQueryValue(startIso) +
            "&end=" + encodeQueryValue(endIso)
        return expectJson(get(path), "calendar $entityId")
    }

    /**
     * State history of [entityIds] from [startIso] until now (`GET /api/history/period/<start>`), as one array of
     * rows per entity. Makes no request when [entityIds] is empty.
     *
     * Asks for minimal rows without attributes: full rows of a busy sensor can run to megabytes.
     *
     * @throws HaException when Home Assistant answers with an error.
     * @throws IOException when no configured URL can be reached.
     */
    fun history(entityIds: List<String>, startIso: String): Json {
        if (entityIds.isEmpty()) {
            return Json.Arr(emptyList())
        }
        val path = "/api/history/period/" + encodePathSegment(startIso) +
            "?filter_entity_id=" + encodeQueryValue(entityIds.joinToString(",")) +
            "&minimal_response&no_attributes"
        return expectJson(get(path), "history")
    }

    /**
     * Whether a Home Assistant answers at [baseUrl], for checking a URL before logging in. Blocking.
     *
     * Sends no token: `GET /api/` answers 401 without one, which still proves the URL leads to Home Assistant.
     * Returns false for a malformed or unreachable URL.
     */
    fun ping(baseUrl: String): Boolean {
        return try {
            val request = HttpRequest.get(normalizeBaseUrl(baseUrl) + "/api/", bearerToken = null)
            val response = HttpTransport.execute(request)
            response.status == HTTP_OK || response.status == HTTP_UNAUTHORIZED
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Downloads an image an entity points to, such as a media player's `entity_picture` or a camera snapshot.
     *
     * An `entity_picture` can name any host (some integrations put third-party artwork URLs there), and fetching it
     * would leak the user's IP address and what they are playing. So only the user's own Home Assistant is contacted:
     * - a path (`/api/media_player_proxy/...`) is fetched from the configured URLs, primary first;
     * - an absolute URL is fetched only when its scheme, host and port match a configured URL;
     * - anything else returns null without a request.
     *
     * The bearer token is sent, because Home Assistant's image proxies need it.
     *
     * @throws HaException when Home Assistant answers with an error or a redirect.
     * @throws IOException when the image's host cannot be reached.
     */
    fun fetchImage(pathOrUrl: String): ByteArray? {
        if (isPathOnly(pathOrUrl)) {
            return firstReachable(configuredBaseUrls()) { baseUrl ->
                expectImage(sendAuthorized(baseUrl) { token -> HttpRequest.get(baseUrl + pathOrUrl, token) })
            }
        }
        val owningBaseUrl = configuredBaseUrls().firstOrNull { baseUrl -> isSameOrigin(baseUrl, pathOrUrl) }
            ?: return null
        return expectImage(sendAuthorized(owningBaseUrl) { token -> HttpRequest.get(pathOrUrl, token) })
    }

    private fun get(pathAndQuery: String): HttpResponse {
        return firstReachable(configuredBaseUrls()) { baseUrl ->
            sendAuthorized(baseUrl) { token -> HttpRequest.get(baseUrl + pathAndQuery, token) }
        }
    }

    private fun post(pathAndQuery: String, data: Json.Obj?): HttpResponse {
        val body = (data ?: Json.obj()).toString()
        return firstReachable(configuredBaseUrls()) { baseUrl ->
            sendAuthorized(baseUrl) { token -> HttpRequest.postJson(baseUrl + pathAndQuery, token, body) }
        }
    }

    /** Sends a request with a bearer token and, after a 401 on an OAuth token, once more with a refreshed one. */
    private fun sendAuthorized(baseUrl: String, buildRequest: (bearerToken: String) -> HttpRequest): HttpResponse {
        val firstToken = tokenProvider.accessToken(baseUrl, forceRefresh = false)
        val firstResponse = HttpTransport.execute(buildRequest(firstToken))
        val canRefresh = tokenStore.longLivedToken == null
        if (firstResponse.status != HTTP_UNAUTHORIZED || !canRefresh) {
            return firstResponse
        }
        val refreshedToken = tokenProvider.accessToken(baseUrl, forceRefresh = true)
        return HttpTransport.execute(buildRequest(refreshedToken))
    }

    private fun configuredBaseUrls(): List<String> {
        return listOf(tokenStore.baseUrl, tokenStore.fallbackUrl)
            .map { normalizeBaseUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun servicePath(domain: String, service: String): String {
        return "/api/services/" + encodePathSegment(domain) + "/" + encodePathSegment(service)
    }

    private fun expectSuccess(response: HttpResponse, operation: String) {
        if (!response.isSuccessful) {
            throw HaException("Home Assistant refused $operation (HTTP ${response.status})", response.status)
        }
    }

    private fun expectJson(response: HttpResponse, operation: String): Json {
        expectSuccess(response, operation)
        return Json.parseOrNull(response.bodyText())
            ?: throw HaException("Home Assistant sent invalid JSON for $operation", response.status)
    }

    private fun expectImage(response: HttpResponse): ByteArray {
        expectSuccess(response, "the image")
        return response.body
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404

        /**
         * A path on the configured host. `//host/x` is a scheme-relative URL to another host, and anything without
         * a leading slash could change the host once appended to the base URL (`@evil.example/x`).
         */
        fun isPathOnly(pathOrUrl: String): Boolean {
            return pathOrUrl.startsWith("/") && !pathOrUrl.startsWith("//")
        }

        /**
         * Compares parsed scheme, host and port. A string-prefix check would accept
         * `https://ha.example.com.evil.example/` and `https://ha.example.com@evil.example/`.
         */
        fun isSameOrigin(baseUrl: String, url: String): Boolean {
            val base = parseHttpUri(baseUrl) ?: return false
            val candidate = parseHttpUri(url) ?: return false
            if (candidate.rawUserInfo != null || candidate.host == null) {
                return false
            }
            return base.scheme.equals(candidate.scheme, ignoreCase = true) &&
                base.host.equals(candidate.host, ignoreCase = true) &&
                effectivePort(base) == effectivePort(candidate)
        }

        fun parseHttpUri(url: String): URI? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            return if (scheme == "http" || scheme == "https") uri else null
        }

        fun effectivePort(uri: URI): Int {
            if (uri.port != -1) {
                return uri.port
            }
            return if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        }
    }
}
