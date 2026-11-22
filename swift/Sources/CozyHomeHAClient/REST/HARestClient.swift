import Foundation

/// The endpoints of Home Assistant's [REST API](https://developers.home-assistant.io/docs/api/rest/) the app uses.
///
/// Each call goes to `TokenStore.baseURL` first and, only if that URL cannot be reached at all, to
/// `TokenStore.fallbackURL`. An HTTP error throws `HAError` without trying the fallback, since both URLs lead to the
/// same instance.
///
/// A 401 is retried once with a refreshed OAuth token, because an access token can be revoked early (after a server
/// restart, for example). Long-lived tokens cannot be refreshed, so they are not retried.
///
/// Redirects are never followed, so the bearer token cannot be forwarded to another host.
public struct HARestClient: Sendable {
    private let tokenStore: any TokenStore
    private let tokenManager: TokenManager

    /// - Parameter tokenManager: the same manager the WebSocket client uses, so both share one refresh.
    public init(tokenStore: any TokenStore, tokenManager: TokenManager) {
        self.tokenStore = tokenStore
        self.tokenManager = tokenManager
    }

    private var transport: HTTPTransport { tokenManager.transport }

    /// All entity states (`GET /api/states`), as an array of state objects.
    ///
    /// - Throws: `HAError` when Home Assistant answers with an error; `URLError` when no configured URL is reachable.
    public func states() async throws -> JSONValue {
        try expectJSON(await get("/api/states"), operation: "states")
    }

    /// Calls a service (`POST /api/services/<domain>/<service>`), for example `light.turn_on`.
    ///
    /// - Parameter data: the service data, such as `["entity_id": "light.kitchen"]`.
    /// - Throws: `HAError` when Home Assistant rejects the call (an unknown service or invalid data gives 400).
    public func callService(domain: String, service: String, data: [String: JSONValue] = [:]) async throws {
        let response = try await post(servicePath(domain: domain, service: service), data: data)
        try expectSuccess(response, operation: "\(domain).\(service)")
    }

    /// Calls a service that returns data, such as `weather.get_forecasts`, and returns the `service_response`
    /// member of the answer, or nil when there is none.
    public func callServiceWithResponse(
        domain: String,
        service: String,
        data: [String: JSONValue] = [:]
    ) async throws -> JSONValue? {
        let response = try await post(servicePath(domain: domain, service: service) + "?return_response", data: data)
        return try expectJSON(response, operation: "\(domain).\(service)")["service_response"]
    }

    /// State history of `entityIDs` from `start` until now (`GET /api/history/period/<start>`), as one array of rows
    /// per entity. Makes no request when `entityIDs` is empty.
    ///
    /// Asks for minimal rows without attributes: full rows of a busy sensor can run to megabytes.
    public func history(entityIDs: [String], since start: Date) async throws -> JSONValue {
        if entityIDs.isEmpty {
            return .array([])
        }
        let path = "/api/history/period/" + encodePathSegment(start.formatted(.iso8601))
            + "?filter_entity_id=" + encodeQueryValue(entityIDs.joined(separator: ","))
            + "&minimal_response&no_attributes"
        return try expectJSON(await get(path), operation: "history")
    }

    /// Downloads an image an entity points to, such as a media player's `entity_picture` or a camera snapshot.
    ///
    /// An `entity_picture` can name any host (some integrations put third-party artwork URLs there), and fetching it
    /// would leak the user's IP address and what they are playing, and send the token along. So only the user's own
    /// Home Assistant is contacted:
    /// - a path (`/api/media_player_proxy/...`) is fetched from the configured URLs, primary first;
    /// - an absolute URL is fetched only when its scheme, host and port match a configured URL;
    /// - anything else returns nil without a request.
    ///
    /// - Throws: `HAError` when Home Assistant answers with an error or a redirect; `URLError` when unreachable.
    public func image(_ pathOrURL: String) async throws -> Data? {
        let baseURLs = configuredBaseURLs(tokenStore)
        if Self.isPathOnly(pathOrURL) {
            return try await firstReachable(baseURLs) { baseURL in
                try expectImage(await sendAuthorized(baseURL: baseURL, url: URL(string: baseURL + pathOrURL)) { url, token in
                    .get(url, bearerToken: token)
                })
            }
        }
        guard let owningBaseURL = baseURLs.first(where: { baseURL in Self.isSameOrigin(baseURL, pathOrURL) }) else {
            return nil
        }
        return try expectImage(await sendAuthorized(baseURL: owningBaseURL, url: URL(string: pathOrURL)) { url, token in
            .get(url, bearerToken: token)
        })
    }

    /// Whether Home Assistant answers at `baseURL`, for checking a URL before logging in.
    ///
    /// Sends no token: `GET /api/` answers 401 without one, which still proves the URL leads to Home Assistant.
    /// Returns false for a malformed or unreachable URL.
    public func ping(_ baseURL: String) async -> Bool {
        guard let url = URL(string: normalizeBaseURL(baseURL) + "/api/"),
              let response = try? await transport.execute(.get(url, bearerToken: nil)) else {
            return false
        }
        return response.status == 200 || response.status == httpUnauthorized
    }

    // MARK: - Requests

    private func get(_ pathAndQuery: String) async throws -> HTTPResponse {
        try await firstReachable(configuredBaseURLs(tokenStore)) { baseURL in
            try await sendAuthorized(baseURL: baseURL, url: URL(string: baseURL + pathAndQuery)) { url, token in
                .get(url, bearerToken: token)
            }
        }
    }

    private func post(_ pathAndQuery: String, data: [String: JSONValue]) async throws -> HTTPResponse {
        let body = try JSONValue.object(data).encoded()
        return try await firstReachable(configuredBaseURLs(tokenStore)) { baseURL in
            try await sendAuthorized(baseURL: baseURL, url: URL(string: baseURL + pathAndQuery)) { url, token in
                .postJSON(url, bearerToken: token, body: body)
            }
        }
    }

    /// Sends a request with a bearer token and, after a 401 on an OAuth token, once more with a refreshed one.
    private func sendAuthorized(
        baseURL: String,
        url: URL?,
        buildRequest: (URL, _ bearerToken: String) -> HTTPRequest
    ) async throws -> HTTPResponse {
        guard let url else {
            throw URLError(.badURL)
        }
        let firstToken = try await tokenManager.accessToken(for: baseURL)
        let firstResponse = try await transport.execute(buildRequest(url, firstToken))
        guard firstResponse.status == httpUnauthorized, tokenStore.longLivedToken == nil else {
            return firstResponse
        }
        let refreshedToken = try await tokenManager.accessToken(for: baseURL, replacing: firstToken)
        return try await transport.execute(buildRequest(url, refreshedToken))
    }

    private func servicePath(domain: String, service: String) -> String {
        "/api/services/" + encodePathSegment(domain) + "/" + encodePathSegment(service)
    }

    private func expectSuccess(_ response: HTTPResponse, operation: String) throws {
        guard response.isSuccessful else {
            throw HAError("Home Assistant refused \(operation) (HTTP \(response.status))", httpStatus: response.status)
        }
    }

    private func expectJSON(_ response: HTTPResponse, operation: String) throws -> JSONValue {
        try expectSuccess(response, operation: operation)
        guard let json = try? JSONValue(parsing: response.body) else {
            throw HAError("Home Assistant sent invalid JSON for \(operation)", httpStatus: response.status)
        }
        return json
    }

    private func expectImage(_ response: HTTPResponse) throws -> Data {
        try expectSuccess(response, operation: "the image")
        return response.body
    }

    // MARK: - Origin checks

    /// A path on the configured host. `//host/x` is a scheme-relative URL to another host, `/\host` is read as one by
    /// lenient parsers, and anything without a leading slash could change the host once appended to the base URL
    /// (`@evil.example/x`).
    internal static func isPathOnly(_ pathOrURL: String) -> Bool {
        pathOrURL.hasPrefix("/") && !pathOrURL.hasPrefix("//") && !pathOrURL.hasPrefix("/\\")
    }

    /// Compares parsed scheme, host and port, and refuses user info. A string-prefix check would accept
    /// `https://ha.example.com.evil.example/` and `https://ha.example.com@evil.example/`.
    internal static func isSameOrigin(_ baseURL: String, _ candidateURL: String) -> Bool {
        guard let base = HTTPOrigin(baseURL), let candidate = HTTPOrigin(candidateURL), !candidate.hasUserInfo else {
            return false
        }
        return base.scheme == candidate.scheme && base.host == candidate.host && base.port == candidate.port
    }
}

/// The scheme, host and port of an `http` or `https` URL, lowercased and with the default port filled in.
private struct HTTPOrigin {
    let scheme: String
    let host: String
    let port: Int
    let hasUserInfo: Bool

    init?(_ text: String) {
        guard let components = URLComponents(string: text),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host?.lowercased(), !host.isEmpty else {
            return nil
        }
        self.scheme = scheme
        self.host = host
        port = components.port ?? (scheme == "https" ? 443 : 80)
        hasUserInfo = components.user != nil || components.password != nil
    }
}
