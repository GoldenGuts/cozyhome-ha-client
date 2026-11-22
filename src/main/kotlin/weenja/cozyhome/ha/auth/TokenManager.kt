package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HTTP_UNAUTHORIZED
import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.normalizeBaseUrl
import java.time.Clock

/**
 * Hands out bearer tokens, refreshing OAuth access tokens when needed
 * ([refresh docs](https://developers.home-assistant.io/docs/auth_api/#refresh-token)).
 *
 * - A long-lived token, when set, is used as is.
 * - An OAuth access token is reused until [REFRESH_MARGIN_MILLIS] before it expires, then refreshed. The margin covers
 *   clock drift and slow requests.
 * - When Home Assistant refuses the refresh token (HTTP 400 or 401) the user revoked it or the instance was reset.
 *   Retrying cannot help, so the stored login is cleared and an [HaException] with status 401 is thrown.
 *
 * Refreshes are serialized, so parallel callers with an expired token cause one refresh, not several.
 */
class TokenManager(
    private val tokenStore: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenProvider {
    private val refreshLock = Any()

    /**
     * Blocking: may call Home Assistant to refresh the token.
     *
     * @throws HaException with status 401 when there is no login, or Home Assistant revoked it.
     * @throws java.io.IOException when a needed refresh fails because [baseUrl] is unreachable.
     */
    override fun accessToken(baseUrl: String, forceRefresh: Boolean): String {
        tokenStore.longLivedToken?.let { return it }
        synchronized(refreshLock) {
            val tokens = tokenStore.oAuthTokens
                ?: throw HaException("Not logged in to Home Assistant", HTTP_UNAUTHORIZED)
            if (!forceRefresh && isFresh(tokens)) {
                return tokens.accessToken
            }
            return refresh(normalizeBaseUrl(baseUrl), tokens).accessToken
        }
    }

    private fun isFresh(tokens: OAuthTokens): Boolean {
        return clock.millis() < tokens.accessTokenExpiresAtMillis - REFRESH_MARGIN_MILLIS
    }

    private fun refresh(baseUrl: String, tokens: OAuthTokens): OAuthTokens {
        val fields = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to tokens.refreshToken,
            "client_id" to tokens.clientId,
        )
        val response = TokenEndpoint.post(baseUrl, fields)
        if (response.status == 400 || response.status == HTTP_UNAUTHORIZED) {
            tokenStore.clear()
            throw HaException("Home Assistant revoked the login; log in again", HTTP_UNAUTHORIZED)
        }
        if (!response.isSuccessful) {
            throw HaException("Token refresh failed (HTTP ${response.status})", response.status)
        }
        val grant = TokenEndpoint.parseGrant(response)
        // A refresh answers with a new access token only; the refresh token stays the same.
        val refreshed = tokens.copy(
            accessToken = grant.accessToken,
            accessTokenExpiresAtMillis = grant.expiresAtMillis(clock),
        )
        tokenStore.oAuthTokens = refreshed
        return refreshed
    }

    companion object {
        /** An access token is refreshed once it has less than this left. */
        const val REFRESH_MARGIN_MILLIS: Long = 60_000
    }
}
