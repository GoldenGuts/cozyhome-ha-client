package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.normalizeBaseUrl
import weenja.cozyhome.ha.rest.encodeQueryValue
import weenja.cozyhome.ha.rest.isSameOrigin
import weenja.cozyhome.ha.rest.parseHttpUri
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
 * thread. To log in with a passkey, run the same flow in the system browser with a [BrowserClient].
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
        return authorizeUrl(baseUrl, BrowserClient(clientId(baseUrl), redirectUri(baseUrl), STATE))
    }

    /** The login page to open in the system browser. */
    fun authorizeUrl(baseUrl: String, client: BrowserClient): String {
        val query = listOf(
            "response_type=code",
            "client_id=" + encodeQueryValue(client.clientId),
            "redirect_uri=" + encodeQueryValue(client.redirectUri),
            "state=" + encodeQueryValue(client.state),
        )
        return normalizeBaseUrl(baseUrl) + "/auth/authorize?" + query.joinToString("&")
    }

    /**
     * True when the login page is navigating to the redirect URI: stop it there and read the code.
     *
     * Compared parsed, because the page builds the URL with `new URL()`, which lowercases the host and drops a
     * default port: a text comparison misses `http://HomeAssistant.local:8123` and `https://ha.example.com:443`.
     */
    fun isRedirect(baseUrl: String, url: String): Boolean {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val redirectUri = redirectUri(baseUrl)
        return isSameOrigin(redirectUri, withoutQuery) &&
            parseHttpUri(redirectUri)?.rawPath == parseHttpUri(withoutQuery)?.rawPath
    }

    /**
     * The `code` parameter of the redirect, or null when it has none or it is malformed.
     * Home Assistant redirects without a code when the user cancels.
     */
    fun authorizationCode(redirectUrl: String): String? {
        return queryParameter(redirectUrl, "code")
    }

    /**
     * The code of a browser callback, or null when the callback is not [client]'s redirect URI, does not carry its
     * state, or has no code. The state check keeps another app or page from handing this app a code.
     */
    fun authorizationCode(callbackUrl: String, client: BrowserClient): String? {
        val isCallback = callbackUrl.substringBefore('#').substringBefore('?') == client.redirectUri
        if (!isCallback || queryParameter(callbackUrl, "state") != client.state) {
            return null
        }
        return queryParameter(callbackUrl, "code")
    }

    private fun queryParameter(url: String, name: String): String? {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        val rawValue = rawQuery.split("&")
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?: return null
        val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8) }.getOrNull()
        return value?.ifEmpty { null }
    }

    /**
     * Trades the authorization code for an access and a refresh token, and saves them with [baseUrl]. Blocking.
     *
     * @param clientId the client ID the login was started with; pass [BrowserClient.clientId] after a browser login.
     * @throws HaException when Home Assistant rejects the code (codes are single-use and expire after ten minutes).
     * @throws java.io.IOException when Home Assistant cannot be reached.
     */
    fun exchangeCode(baseUrl: String, code: String, clientId: String = clientId(baseUrl)) {
        val base = normalizeBaseUrl(baseUrl)
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
