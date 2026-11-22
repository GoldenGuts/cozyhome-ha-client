package weenja.cozyhome.ha.auth

/**
 * Gives a valid access token for a Home Assistant base URL.
 *
 * The REST client and the WebSocket session both authenticate with it.
 * Implementations may block (to refresh an expired token), so call it from a background thread.
 */
fun interface AccessTokenProvider {
    /**
     * @param baseUrl the Home Assistant URL the token is for, without a trailing slash.
     * @param forceRefresh true after Home Assistant answered 401 with the current token.
     */
    fun accessToken(baseUrl: String, forceRefresh: Boolean): String
}
