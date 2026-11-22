import Foundation

/// Where the Home Assistant URLs and credentials are kept between launches.
///
/// URLs are stored without a trailing slash, for example `https://ha.example.com` or `http://192.168.1.10:8123`.
/// Implementations must be safe to call from any thread; `KeychainTokenStore` is the one the iOS app uses,
/// `InMemoryTokenStore` is enough for tests and tools.
public protocol TokenStore: AnyObject, Sendable {
    /// The primary Home Assistant URL, or an empty string before setup.
    var baseURL: String { get set }

    /// An optional second URL, tried when the primary one is unreachable (typically a LAN address); may be empty.
    var fallbackURL: String { get set }

    /// A long-lived access token created in the user's Home Assistant profile. When set it wins over `oAuthTokens`:
    /// it cannot be refreshed, and the user chose it on purpose.
    var longLivedToken: String? { get set }

    /// The tokens from the OAuth login, or nil when the user has not logged in that way.
    var oAuthTokens: OAuthTokens? { get set }

    /// Forgets every credential but keeps the URLs, as logging out does.
    func clearCredentials()
}

/// The result of Home Assistant's OAuth login.
public struct OAuthTokens: Codable, Equatable, Sendable {
    /// The short-lived bearer token (Home Assistant issues them for 30 minutes).
    public var accessToken: String

    /// When `accessToken` expires.
    public var accessTokenExpiresAt: Date

    /// Buys new access tokens until the user revokes it.
    public var refreshToken: String

    /// The `client_id` the refresh token was issued to; Home Assistant checks it on every refresh.
    public var clientID: String

    public init(accessToken: String, accessTokenExpiresAt: Date, refreshToken: String, clientID: String) {
        self.accessToken = accessToken
        self.accessTokenExpiresAt = accessTokenExpiresAt
        self.refreshToken = refreshToken
        self.clientID = clientID
    }
}

extension OAuthTokens: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Leaves the tokens out, so a stray `print`, log line or crash report cannot leak them.
    public var description: String {
        "OAuthTokens(clientID: \(clientID), accessTokenExpiresAt: \(accessTokenExpiresAt))"
    }

    public var debugDescription: String { description }

    /// `dump()` and the debugger's variable view use the mirror, not `description`.
    public var customMirror: Mirror {
        Mirror(self, children: ["clientID": clientID, "accessTokenExpiresAt": accessTokenExpiresAt])
    }
}

/// A value behind a lock, for the few places that need synchronous, thread-safe state.
///
/// `@unchecked` because the compiler cannot see that every access goes through `lock`; nothing else touches `value`.
internal final class Locked<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }

    var current: Value {
        withLock { value in value }
    }
}
