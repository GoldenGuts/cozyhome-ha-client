package weenja.cozyhome.ha.auth

import java.util.UUID

/**
 * A login in the system browser instead of a WebView.
 *
 * A WebView cannot use passkeys or security keys for a domain the app does not own; the system browser can. The
 * browser returns to the app through a custom URL scheme, which Home Assistant accepts only when the page at
 * [clientId] lists [redirectUri] in a `<link rel="redirect_uri">`. Home Assistant fetches that page itself.
 *
 * Create one per login: [state] is random, and a callback that does not carry it is ignored.
 *
 * @param clientId the URL of the client page, for example `https://example.com/app/login`.
 * @param redirectUri the app's callback, for example `exampleapp://auth-callback`.
 */
class BrowserClient(
    val clientId: String,
    val redirectUri: String,
    val state: String = UUID.randomUUID().toString(),
)
