package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.normalizeBaseUrl

/**
 * A [TokenStore] that keeps everything in memory, for tests, command-line tools and short-lived sessions.
 *
 * URLs are trimmed and lose any trailing slash on assignment. A blank long-lived token counts as none.
 */
class InMemoryTokenStore(
    baseUrl: String = "",
    fallbackUrl: String = "",
    longLivedToken: String? = null,
    oAuthTokens: OAuthTokens? = null,
) : TokenStore {
    @Volatile
    override var baseUrl: String = normalizeBaseUrl(baseUrl)
        set(value) {
            field = normalizeBaseUrl(value)
        }

    @Volatile
    override var fallbackUrl: String = normalizeBaseUrl(fallbackUrl)
        set(value) {
            field = normalizeBaseUrl(value)
        }

    @Volatile
    override var longLivedToken: String? = normalizeToken(longLivedToken)
        set(value) {
            field = normalizeToken(value)
        }

    @Volatile
    override var oAuthTokens: OAuthTokens? = oAuthTokens

    override fun clear() {
        longLivedToken = null
        oAuthTokens = null
    }

    private companion object {
        fun normalizeToken(token: String?): String? = token?.trim()?.takeIf { it.isNotEmpty() }
    }
}
