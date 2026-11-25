import Foundation

/// A login in the system browser (`ASWebAuthenticationSession`) instead of a web view.
///
/// A web view cannot use passkeys or security keys for a domain the app does not own; the system browser can. The
/// browser returns to the app through a custom URL scheme, which Home Assistant accepts only when the page at
/// `clientID` lists `redirectURI` in a `<link rel="redirect_uri">`. Home Assistant fetches that page itself.
///
/// Create one per login: `state` is random, and a callback that does not carry it is ignored.
public struct BrowserClient: Sendable {
    public let clientID: String
    public let redirectURI: String
    public let state: String

    /// - Parameters:
    ///   - clientID: the URL of the client page, for example `https://example.com/app/login`.
    ///   - redirectURI: the app's callback, for example `exampleapp://auth-callback`.
    public init(clientID: String, redirectURI: String, state: String = UUID().uuidString) {
        self.clientID = clientID
        self.redirectURI = redirectURI
        self.state = state
    }
}
