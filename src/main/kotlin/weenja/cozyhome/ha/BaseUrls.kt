package weenja.cozyhome.ha

import java.io.IOException

internal const val HTTP_UNAUTHORIZED = 401

/** Trims whitespace and trailing slashes, so a path can be appended with one `/`. */
internal fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

/**
 * Runs [attempt] against each non-blank URL in turn and returns the first result.
 *
 * Only a network failure moves on to the next URL. An [HaException] means Home Assistant answered, and the other
 * URLs lead to the same instance, so it is thrown at once.
 */
internal fun <T> firstReachable(baseUrls: List<String>, attempt: (baseUrl: String) -> T): T {
    var lastNetworkError: IOException? = null
    for (baseUrl in baseUrls.map(::normalizeBaseUrl).filter { it.isNotEmpty() }.distinct()) {
        try {
            return attempt(baseUrl)
        } catch (answer: HaException) {
            throw answer
        } catch (networkError: IOException) {
            lastNetworkError = networkError
        }
    }
    throw lastNetworkError ?: IOException("No Home Assistant URL is configured")
}
