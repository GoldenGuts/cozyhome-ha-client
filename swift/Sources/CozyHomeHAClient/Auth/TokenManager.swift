import Foundation

/// Hands out bearer tokens, refreshing OAuth access tokens when needed
/// ([refresh docs](https://developers.home-assistant.io/docs/auth_api/#refresh-token)).
///
/// - A long-lived token, when set, is used as is.
/// - An OAuth access token is reused until `refreshMargin` before it expires, then refreshed. The margin covers clock
///   drift and slow requests.
/// - When Home Assistant refuses the refresh token (HTTP 400 or 401), the user revoked it or the instance was reset.
///   Retrying cannot help, so the stored login is cleared and an `HAError` with status 401 is thrown.
///
/// An actor alone does not stop two refreshes from running at once: it is re-entrant, so a second caller gets in
/// while the first awaits the network. The refresh in progress is therefore kept as a task, and every caller that
/// arrives during it awaits that same task. Several widgets updating together cause one refresh, not several racing
/// ones that overwrite each other's tokens.
public actor TokenManager {
    /// An access token is refreshed once it has less than this left.
    public static let refreshMargin: TimeInterval = 60

    private let tokenStore: any TokenStore
    /// Shared with the REST client and WebSocket sessions built on this manager, so they all use one `URLSession`.
    internal nonisolated let transport: HTTPTransport
    private let now: @Sendable () -> Date
    private var refreshInProgress: Task<String, any Error>?

    public init(tokenStore: any TokenStore) {
        self.init(tokenStore: tokenStore, transport: HTTPTransport(), now: { Date() })
    }

    internal init(tokenStore: any TokenStore, transport: HTTPTransport, now: @escaping @Sendable () -> Date) {
        self.tokenStore = tokenStore
        self.transport = transport
        self.now = now
    }

    /// A bearer token for `baseURL`, refreshed first if it is about to expire.
    ///
    /// - Parameters:
    ///   - baseURL: the Home Assistant URL being called; a refresh goes to the same URL.
    ///   - rejectedToken: the token Home Assistant just answered 401 for. It is refreshed even if it has not expired
    ///     (a server restart can revoke access tokens early), unless another caller has already replaced it.
    /// - Throws: `HAError` with status 401 when there is no login or Home Assistant revoked it; `URLError` when a
    ///   needed refresh cannot reach `baseURL`.
    public func accessToken(for baseURL: String, replacing rejectedToken: String? = nil) async throws -> String {
        if let longLivedToken = tokenStore.longLivedToken {
            return longLivedToken
        }
        if let refreshInProgress {
            return try await refreshInProgress.value
        }
        guard let tokens = tokenStore.oAuthTokens else {
            throw HAError("Not logged in to Home Assistant", httpStatus: httpUnauthorized)
        }
        if isFresh(tokens), tokens.accessToken != rejectedToken {
            return tokens.accessToken
        }
        let refresh = Task { try await self.refresh(baseURL: normalizeBaseURL(baseURL), tokens: tokens) }
        refreshInProgress = refresh
        defer { refreshInProgress = nil }
        return try await refresh.value
    }

    private func isFresh(_ tokens: OAuthTokens) -> Bool {
        now() < tokens.accessTokenExpiresAt.addingTimeInterval(-Self.refreshMargin)
    }

    private func refresh(baseURL: String, tokens: OAuthTokens) async throws -> String {
        let response = try await TokenEndpoint.post(
            baseURL: baseURL,
            fields: [
                "grant_type": "refresh_token",
                "refresh_token": tokens.refreshToken,
                "client_id": tokens.clientID,
            ],
            transport: transport
        )
        if response.status == 400 || response.status == httpUnauthorized {
            // Another process sharing the store (a widget extension) may have logged in again meanwhile; only the
            // login that was refused is forgotten.
            if tokenStore.oAuthTokens?.refreshToken == tokens.refreshToken {
                tokenStore.clearCredentials()
            }
            throw HAError("Home Assistant revoked the login; log in again", httpStatus: httpUnauthorized)
        }
        guard response.isSuccessful else {
            throw HAError("Token refresh failed (HTTP \(response.status))", httpStatus: response.status)
        }
        let grant = try TokenEndpoint.parseGrant(response)
        // A refresh answers with a new access token only; the refresh token stays the same.
        var refreshed = tokens
        refreshed.accessToken = grant.accessToken
        refreshed.accessTokenExpiresAt = now().addingTimeInterval(grant.expiresIn)
        tokenStore.oAuthTokens = refreshed
        return refreshed.accessToken
    }
}
