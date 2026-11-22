package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.normalizeBaseUrl
import weenja.cozyhome.ha.rest.encodeQueryValue
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock

/**
 * Logs in through Home Assistant's own login page ([auth API](https://developers.home-assistant.io/docs/auth_api/)).
 *
 * Home Assistant has no client registration: under IndieAuth the `client_id` is a URL, and the `redirect_uri` must be
 * on the same host. So the Home Assistant URL itself is the client ID, and the redirect goes to a path on that host
 * that nothing serves. Show the login page in an embedded WebView and stop the navigation when [isRedirect] is true;
 * no web page has to be hosted anywhere and the code never leaves the device.
 *
 * Flow: open [authorizeUrl], wait for the redirect, read [authorizationCode], then call [exchangeCode] off the main
 * thread.
 *
 * @param redirectPath any path on the Home Assistant host that it does not serve.
 */
class OAuthLogin(
    private val tokenStore: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
    private val redirectPath: String = DEFAULT_REDIRECT_PATH,
) {
    /** The OAuth `client_id` for [baseUrl]: the Home Assistant URL with a trailing slash, as IndieAuth requires. */
    fun clientId(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/"

    /** Where Home Assistant sends the browser after login. */
    fun redirectUri(baseUrl: String): String = normalizeBaseUrl(baseUrl) + redirectPath

    /**
     * The login page to show.
     *
     * Home Assistant requires a `state` parameter. A fixed value is sent and not checked: the redirect is caught
     * inside the WebView before it loads, so no other app or site can inject one.
     */
    fun authorizeUrl(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        val query = listOf(
            "response_type=code",
            "client_id=" + encodeQueryValue(clientId(base)),
            "redirect_uri=" + encodeQueryValue(redirectUri(base)),
            "state=" + encodeQueryValue(STATE),
        )
        return "$base/auth/authorize?" + query.joinToString("&")
    }

    /** True when the login page is navigating to the redirect URI: stop it there and read the code. */
    fun isRedirect(baseUrl: String, url: String): Boolean {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        return withoutQuery == redirectUri(baseUrl)
    }

    /**
     * The `code` parameter of the redirect, or null when it has none or it is malformed.
     * Home Assistant redirects without a code when the user cancels.
     */
    fun authorizationCode(redirectUrl: String): String? {
        val rawQuery = runCatching { URI(redirectUrl).rawQuery }.getOrNull() ?: return null
        val rawCode = rawQuery.split("&")
            .firstOrNull { it.substringBefore("=") == "code" }
            ?.substringAfter("=", "")
            ?: return null
        val code = runCatching { URLDecoder.decode(rawCode, StandardCharsets.UTF_8) }.getOrNull()
        return code?.ifEmpty { null }
    }

    /**
     * Trades the authorization code for an access and a refresh token, and saves them with [baseUrl]. Blocking.
     *
     * @throws HaException when Home Assistant rejects the code (codes are single-use and expire after ten minutes).
     * @throws java.io.IOException when Home Assistant cannot be reached.
     */
    fun exchangeCode(baseUrl: String, code: String) {
        val base = normalizeBaseUrl(baseUrl)
        val clientId = clientId(base)
        val fields = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId,
        )
        val response = TokenEndpoint.post(base, fields)
        if (!response.isSuccessful) {
            throw HaException("Home Assistant rejected the login (HTTP ${response.status})", response.status)
        }
        val grant = TokenEndpoint.parseGrant(response)
        val refreshToken = grant.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            throw HaException("Home Assistant sent no refresh token", response.status)
        }
        tokenStore.baseUrl = base
        tokenStore.oAuthTokens = OAuthTokens(
            accessToken = grant.accessToken,
            accessTokenExpiresAtMillis = grant.expiresAtMillis(clock),
            refreshToken = refreshToken,
            clientId = clientId,
        )
    }

    companion object {
        const val DEFAULT_REDIRECT_PATH: String = "/cozyhome/callback"

        private const val STATE = "cozyhome"
    }
}
