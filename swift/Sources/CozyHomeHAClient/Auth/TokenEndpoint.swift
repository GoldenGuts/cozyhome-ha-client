import Foundation

/// The fields of a successful answer from Home Assistant's token endpoint.
internal struct TokenGrant: Sendable, CustomStringConvertible {
    var accessToken: String
    var expiresIn: TimeInterval
    var refreshToken: String?

    var description: String { "TokenGrant(expiresIn: \(expiresIn))" }
}

/// `POST /auth/token`, shared by the code exchange and the refresh
/// ([docs](https://developers.home-assistant.io/docs/auth_api/#token)).
internal enum TokenEndpoint {
    /// Home Assistant always sends `expires_in`; this is its usual value, used only if the field is missing.
    static let defaultExpiresIn: TimeInterval = 1800

    static func post(
        baseURL: String,
        fields: KeyValuePairs<String, String>,
        transport: HTTPTransport
    ) async throws -> HTTPResponse {
        guard let url = URL(string: baseURL + "/auth/token") else {
            throw URLError(.badURL)
        }
        return try await transport.execute(.postForm(url, fields: fields))
    }

    /// Reads a 2xx answer; a body without an access token is a broken answer, not a network problem.
    static func parseGrant(_ response: HTTPResponse) throws -> TokenGrant {
        let body = try? JSONValue(parsing: response.body)
        guard let accessToken = body?["access_token"]?.stringValue, !accessToken.isEmpty else {
            throw HAError("Home Assistant sent a token response without an access token", httpStatus: response.status)
        }
        return TokenGrant(
            accessToken: accessToken,
            expiresIn: body?["expires_in"]?.doubleValue ?? defaultExpiresIn,
            refreshToken: body?["refresh_token"]?.stringValue
        )
    }
}
