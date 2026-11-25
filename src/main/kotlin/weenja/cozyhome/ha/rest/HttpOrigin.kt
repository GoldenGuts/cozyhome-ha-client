package weenja.cozyhome.ha.rest

import java.net.URI

/**
 * Compares parsed scheme, host and port, and refuses user info. A string-prefix check would accept
 * `https://ha.example.com.evil.example/` and `https://ha.example.com@evil.example/`.
 */
internal fun isSameOrigin(baseUrl: String, url: String): Boolean {
    val base = parseHttpUri(baseUrl) ?: return false
    val candidate = parseHttpUri(url) ?: return false
    if (candidate.rawUserInfo != null || candidate.host == null) {
        return false
    }
    return base.scheme.equals(candidate.scheme, ignoreCase = true) &&
        base.host.equals(candidate.host, ignoreCase = true) &&
        effectivePort(base) == effectivePort(candidate)
}

internal fun parseHttpUri(url: String): URI? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    return if (scheme == "http" || scheme == "https") uri else null
}

private fun effectivePort(uri: URI): Int {
    if (uri.port != -1) {
        return uri.port
    }
    return if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
}
