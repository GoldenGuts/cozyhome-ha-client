package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.json.Json
import weenja.cozyhome.ha.rest.HttpRequest
import weenja.cozyhome.ha.rest.HttpResponse
import weenja.cozyhome.ha.rest.HttpTransport
import java.time.Clock

/** The fields of a successful answer from Home Assistant's token endpoint. */
internal class TokenGrant(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
) {
    fun expiresAtMillis(clock: Clock): Long = clock.millis() + expiresInSeconds * 1000

    override fun toString(): String = "TokenGrant(expiresInSeconds=$expiresInSeconds)"
}

/**
 * `POST /auth/token`, shared by the code exchange and the refresh
 * ([docs](https://developers.home-assistant.io/docs/auth_api/#token)).
 */
internal object TokenEndpoint {
    private const val PATH = "/auth/token"

    /** Home Assistant always sends `expires_in`; this is its usual value, used only if the field is missing. */
    private const val DEFAULT_EXPIRES_IN_SECONDS = 1800L

    /** Sends the form without a bearer token: the grant itself is the credential. */
    fun post(baseUrl: String, fields: Map<String, String>): HttpResponse {
        return HttpTransport.execute(HttpRequest.postForm(baseUrl + PATH, fields))
    }

    /** Reads a 2xx answer; a body without an access token is a broken answer, not a network problem. */
    fun parseGrant(response: HttpResponse): TokenGrant {
        val body = Json.parseOrNull(response.bodyText())
        val accessToken = body?.str("access_token")
        if (body == null || accessToken.isNullOrEmpty()) {
            throw HaException("Home Assistant sent a token response without an access token", response.status)
        }
        val expiresInSeconds = body.num("expires_in")?.toLong() ?: DEFAULT_EXPIRES_IN_SECONDS
        return TokenGrant(accessToken, expiresInSeconds, body.str("refresh_token"))
    }
}
