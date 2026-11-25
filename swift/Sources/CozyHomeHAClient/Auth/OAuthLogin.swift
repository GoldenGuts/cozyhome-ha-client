import Foundation

/// Logs in through Home Assistant's own login page ([auth API](https://developers.home-assistant.io/docs/auth_api/)).
///
/// Home Assistant has no client registration: under IndieAuth the `client_id` is a URL, and the `redirect_uri` must
/// be on the same host. So the Home Assistant URL itself is the client ID, and the redirect goes to a path on that
/// host that nothing serves. Show the login page in a `WKWebView` and cancel the navigation when `isRedirect` is
/// true; no web page has to be hosted anywhere and the code never leaves the device.
///
/// Flow: load `authorizeURL`, wait for the redirect, read `authorizationCode`, then call `exchangeCode`. To log in with
/// a passkey, run the same flow in the system browser with a `BrowserClient`.
public struct OAuthLogin: Sendable {
    public static let defaultRedirectPath = "/cozyhome/callback"

    /// Home Assistant requires a `state` parameter. A fixed value is sent and not checked: the redirect is caught
    /// inside the web view before it loads, so no other app or site can hand the app a code.
    private static let state = "cozyhome"

    private let tokenStore: any TokenStore
    private let transport: HTTPTransport
    private let now: @Sendable () -> Date
    private let redirectPath: String

    /// - Parameter redirectPath: any path on the Home Assistant host that it does not serve.
    public init(tokenStore: any TokenStore, redirectPath: String = OAuthLogin.defaultRedirectPath) {
        self.init(tokenStore: tokenStore, transport: HTTPTransport(), now: { Date() }, redirectPath: redirectPath)
    }

    internal init(
        tokenStore: any TokenStore,
        transport: HTTPTransport,
        now: @escaping @Sendable () -> Date,
        redirectPath: String = OAuthLogin.defaultRedirectPath
    ) {
        self.tokenStore = tokenStore
        self.transport = transport
        self.now = now
        self.redirectPath = redirectPath
    }

    /// The OAuth `client_id` for `baseURL`: the Home Assistant URL with a trailing slash, as IndieAuth requires.
    public func clientID(for baseURL: String) -> String {
        normalizeBaseURL(baseURL) + "/"
    }

    /// Where Home Assistant sends the web view after login.
    public func redirectURI(for baseURL: String) -> String {
        normalizeBaseURL(baseURL) + redirectPath
    }

    /// The login page to show, or nil when `baseURL` is not a valid URL.
    public func authorizeURL(for baseURL: String) -> URL? {
        let client = BrowserClient(clientID: clientID(for: baseURL), redirectURI: redirectURI(for: baseURL), state: Self.state)
        return authorizeURL(for: baseURL, client: client)
    }

    /// The login page to open in the system browser, or nil when `baseURL` is not a valid URL.
    public func authorizeURL(for baseURL: String, client: BrowserClient) -> URL? {
        var components = URLComponents(string: normalizeBaseURL(baseURL) + "/auth/authorize")
        components?.percentEncodedQueryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: encodeQueryValue(client.clientID)),
            URLQueryItem(name: "redirect_uri", value: encodeQueryValue(client.redirectURI)),
            URLQueryItem(name: "state", value: encodeQueryValue(client.state)),
        ]
        return components?.url
    }

    /// True when the web view is navigating to the redirect URI: cancel that navigation and read the code.
    ///
    /// Compared parsed, because the page builds the URL with `new URL()`, which lowercases the host and drops a
    /// default port: a text comparison misses `http://HomeAssistant.local:8123` and `https://ha.example.com:443`.
    /// The whole path must match, so `/cozyhome/callback-other` does not.
    public func isRedirect(_ url: URL, for baseURL: String) -> Bool {
        let redirectURI = redirectURI(for: baseURL)
        return HARestClient.isSameOrigin(redirectURI, url.absoluteString)
            && URLComponents(string: redirectURI)?.percentEncodedPath
                == URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath
    }

    /// The `code` parameter of the redirect, or nil when it has none. Home Assistant redirects without a code when
    /// the user cancels.
    public func authorizationCode(from redirectURL: URL) -> String? {
        let components = URLComponents(url: redirectURL, resolvingAgainstBaseURL: false)
        guard let code = components?.queryItems?.first(where: { item in item.name == "code" })?.value,
              !code.isEmpty else {
            return nil
        }
        return code
    }

    /// The code of a browser callback, or nil when the callback is not `client`'s redirect URI, does not carry its
    /// state, or has no code. The state check keeps another app or page from handing this app a code.
    public func authorizationCode(from callbackURL: URL, client: BrowserClient) -> String? {
        guard var components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false) else { return nil }
        let state = components.queryItems?.first(where: { item in item.name == "state" })?.value
        components.query = nil
        components.fragment = nil
        guard components.string == client.redirectURI, state == client.state else { return nil }
        return authorizationCode(from: callbackURL)
    }

    /// Trades the authorization code for an access and a refresh token, and saves them with `baseURL` as the
    /// primary URL. A long-lived token saved earlier is removed, since it would win over the new login.
    ///
    /// - Parameter clientID: the client ID the login was started with; pass `BrowserClient.clientID` after a browser
    ///   login. Nil means the Home Assistant URL.
    /// - Throws: `HAError` when Home Assistant rejects the code (codes are single-use and expire after ten minutes);
    ///   `URLError` when Home Assistant cannot be reached.
    public func exchangeCode(_ code: String, baseURL: String, clientID: String? = nil) async throws {
        let base = normalizeBaseURL(baseURL)
        let clientID = clientID ?? self.clientID(for: base)
        let response = try await TokenEndpoint.post(
            baseURL: base,
            fields: [
                "grant_type": "authorization_code",
                "code": code,
                "client_id": clientID,
            ],
            transport: transport
        )
        guard response.isSuccessful else {
            throw HAError("Home Assistant rejected the login (HTTP \(response.status))", httpStatus: response.status)
        }
        let grant = try TokenEndpoint.parseGrant(response)
        guard let refreshToken = grant.refreshToken, !refreshToken.isEmpty else {
            throw HAError("Home Assistant sent no refresh token", httpStatus: response.status)
        }
        tokenStore.baseURL = base
        tokenStore.longLivedToken = nil
        tokenStore.oAuthTokens = OAuthTokens(
            accessToken: grant.accessToken,
            accessTokenExpiresAt: now().addingTimeInterval(grant.expiresIn),
            refreshToken: refreshToken,
            clientID: clientID
        )
    }
}
