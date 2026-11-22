import Foundation

/// A `TokenStore` that keeps everything in memory, for tests, command-line tools and short-lived sessions.
///
/// URLs are trimmed and lose any trailing slash on assignment. A blank long-lived token counts as none.
public final class InMemoryTokenStore: TokenStore {
    private struct Contents: Sendable {
        var baseURL: String
        var fallbackURL: String
        var longLivedToken: String?
        var oAuthTokens: OAuthTokens?
    }

    private let contents: Locked<Contents>

    public init(
        baseURL: String = "",
        fallbackURL: String = "",
        longLivedToken: String? = nil,
        oAuthTokens: OAuthTokens? = nil
    ) {
        contents = Locked(Contents(
            baseURL: normalizeBaseURL(baseURL),
            fallbackURL: normalizeBaseURL(fallbackURL),
            longLivedToken: normalizeToken(longLivedToken),
            oAuthTokens: oAuthTokens
        ))
    }

    public var baseURL: String {
        get { contents.current.baseURL }
        set { contents.withLock { stored in stored.baseURL = normalizeBaseURL(newValue) } }
    }

    public var fallbackURL: String {
        get { contents.current.fallbackURL }
        set { contents.withLock { stored in stored.fallbackURL = normalizeBaseURL(newValue) } }
    }

    public var longLivedToken: String? {
        get { contents.current.longLivedToken }
        set { contents.withLock { stored in stored.longLivedToken = normalizeToken(newValue) } }
    }

    public var oAuthTokens: OAuthTokens? {
        get { contents.current.oAuthTokens }
        set { contents.withLock { stored in stored.oAuthTokens = newValue } }
    }

    public func clearCredentials() {
        contents.withLock { stored in
            stored.longLivedToken = nil
            stored.oAuthTokens = nil
        }
    }
}

extension InMemoryTokenStore: CustomStringConvertible {
    /// Says which kind of login is present, never the token itself.
    public var description: String {
        let snapshot = contents.current
        let login = snapshot.longLivedToken != nil ? "long-lived token" : snapshot.oAuthTokens != nil ? "OAuth" : "none"
        return "InMemoryTokenStore(baseURL: \(snapshot.baseURL), fallbackURL: \(snapshot.fallbackURL), login: \(login))"
    }
}

/// A trimmed token, or nil for a blank one.
internal func normalizeToken(_ token: String?) -> String? {
    guard let trimmed = token?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
    return trimmed
}
