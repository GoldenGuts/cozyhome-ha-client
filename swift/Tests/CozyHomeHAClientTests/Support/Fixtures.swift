import Foundation
import Testing
@testable import CozyHomeHAClient

/// Every suite that goes through `StubServer` is nested in here, so they run one at a time.
@Suite(.serialized)
enum StubbedNetworkTests {}

enum Fixture {
    static let primaryURL = "https://ha.example.com"
    static let primaryHost = "ha.example.com"
    static let fallbackURL = "http://192.168.1.10:8123"
    static let fallbackHost = "192.168.1.10"
    static let clientID = "https://ha.example.com/"

    static func oAuthTokens(accessToken: String = "access-1", expiresAt: Date) -> OAuthTokens {
        OAuthTokens(
            accessToken: accessToken,
            accessTokenExpiresAt: expiresAt,
            refreshToken: "refresh-secret",
            clientID: clientID
        )
    }

    /// A store logged in through OAuth whose access token expires `expiresIn` after the clock's time.
    static func loggedInStore(clock: TestClock, expiresIn: TimeInterval = 1800) -> InMemoryTokenStore {
        InMemoryTokenStore(
            baseURL: primaryURL,
            fallbackURL: fallbackURL,
            oAuthTokens: oAuthTokens(expiresAt: clock.now.addingTimeInterval(expiresIn))
        )
    }

    static func tokenResponse(accessToken: String, expiresIn: Int = 1800, refreshToken: String? = nil) -> StubAnswer {
        var members = ["\"access_token\": \"\(accessToken)\"", "\"expires_in\": \(expiresIn)", "\"token_type\": \"Bearer\""]
        if let refreshToken {
            members.append("\"refresh_token\": \"\(refreshToken)\"")
        }
        return .json("{" + members.joined(separator: ", ") + "}")
    }

    static func isTokenRequest(_ request: RecordedRequest) -> Bool {
        request.method == "POST" && request.url.path == "/auth/token"
    }
}
