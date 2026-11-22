import Foundation

/// An answer from Home Assistant that retrying on another URL will not fix: a rejected login, a failed command,
/// an HTTP error status.
///
/// Network failures (timeouts, refused connections) stay `URLError`s, so callers can tell "Home Assistant said no"
/// from "Home Assistant was not reachable".
public struct HAError: Error, Equatable, Sendable, CustomStringConvertible {
    /// What went wrong, in words that are safe to show and to log. Never contains a token.
    public let message: String

    /// The HTTP status code when there is one, else 0.
    public let httpStatus: Int

    public init(_ message: String, httpStatus: Int = 0) {
        self.message = message
        self.httpStatus = httpStatus
    }

    public var description: String { message }
}

extension HAError: LocalizedError {
    public var errorDescription: String? { message }
}

internal let httpUnauthorized = 401

/// Trims whitespace and trailing slashes, so a path can be appended with one `/`.
internal func normalizeBaseURL(_ url: String) -> String {
    var trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
    while trimmed.hasSuffix("/") {
        trimmed.removeLast()
    }
    return trimmed
}

/// The configured URLs in the order they are tried, without blanks or duplicates.
internal func configuredBaseURLs(_ store: any TokenStore) -> [String] {
    var urls: [String] = []
    for url in [store.baseURL, store.fallbackURL].map(normalizeBaseURL) where !url.isEmpty && !urls.contains(url) {
        urls.append(url)
    }
    return urls
}

/// Runs `attempt` against each URL in turn and returns the first result.
///
/// Only a network failure (`URLError`) moves on to the next URL. An `HAError` means Home Assistant answered, and the
/// other URLs lead to the same instance, so it is thrown at once. So is anything else, cancellation included: a
/// cancelled refresh must not quietly start over against the fallback.
internal func firstReachable<Result>(
    _ baseURLs: [String],
    _ attempt: (_ baseURL: String) async throws -> Result
) async throws -> Result {
    var lastNetworkError: URLError?
    for baseURL in baseURLs {
        do {
            return try await attempt(baseURL)
        } catch let networkError as URLError where networkError.code != .cancelled {
            lastNetworkError = networkError
        }
    }
    throw lastNetworkError ?? URLError(.cannotFindHost, userInfo: [
        NSLocalizedDescriptionKey: "No Home Assistant URL is configured",
    ])
}
