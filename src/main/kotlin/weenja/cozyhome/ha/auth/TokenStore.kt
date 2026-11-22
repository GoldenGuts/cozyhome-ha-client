package weenja.cozyhome.ha.auth

/**
 * Where the Home Assistant URLs and credentials are kept between launches.
 *
 * Back it with storage only your app can read, and keep it out of cloud backups so a login does not follow the
 * user to another device. [InMemoryTokenStore] is enough for tests and tools.
 *
 * URLs are stored without a trailing slash, for example `https://ha.example.com` or `http://192.168.1.10:8123`.
 * Implementations must be safe to call from several threads.
 */
interface TokenStore {
    /** The primary Home Assistant URL, or an empty string before setup. */
    var baseUrl: String

    /** An optional second URL, tried when the primary one is unreachable (typically a LAN address); may be empty. */
    var fallbackUrl: String

    /**
     * A long-lived access token created in the user's Home Assistant profile.
     * When set it wins over [oAuthTokens]: it cannot be refreshed, and the user chose it on purpose.
     */
    var longLivedToken: String?

    /** The tokens from the OAuth login, or null when the user has not logged in that way. */
    var oAuthTokens: OAuthTokens?

    /** Forgets every credential (OAuth tokens and the long-lived token) but keeps the URLs, as logging out does. */
    fun clear()
}

/**
 * The result of Home Assistant's OAuth login.
 *
 * @property accessToken the short-lived bearer token (Home Assistant issues them for 30 minutes).
 * @property accessTokenExpiresAtMillis when [accessToken] expires, in epoch milliseconds.
 * @property refreshToken buys new access tokens until the user revokes it.
 * @property clientId the `client_id` the refresh token was issued to; Home Assistant checks it on every refresh.
 */
data class OAuthTokens(
    val accessToken: String,
    val accessTokenExpiresAtMillis: Long,
    val refreshToken: String,
    val clientId: String,
) {
    /** Leaves the tokens out, so a stray log line or crash report cannot leak them. */
    override fun toString(): String {
        return "OAuthTokens(clientId=$clientId, accessTokenExpiresAtMillis=$accessTokenExpiresAtMillis)"
    }
}
