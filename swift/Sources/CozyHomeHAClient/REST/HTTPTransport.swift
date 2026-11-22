import Foundation

/// One HTTP request to Home Assistant.
///
/// Kept separate from `URLRequest` so the token is attached in one place, and so printing a request (in a test
/// failure, a log line) shows the method and URL but never the bearer token.
internal struct HTTPRequest: Sendable, CustomStringConvertible {
    var url: URL
    var method: String
    var bearerToken: String?
    var body: Data?
    var contentType: String?

    var description: String { "HTTPRequest(\(method) \(url.absoluteString))" }

    static func get(_ url: URL, bearerToken: String?) -> HTTPRequest {
        HTTPRequest(url: url, method: "GET", bearerToken: bearerToken, body: nil, contentType: nil)
    }

    static func postJSON(_ url: URL, bearerToken: String?, body: Data) -> HTTPRequest {
        HTTPRequest(url: url, method: "POST", bearerToken: bearerToken, body: body, contentType: "application/json")
    }

    /// An `application/x-www-form-urlencoded` POST, the format Home Assistant's token endpoint expects. Sent without
    /// a bearer token: the grant in the form is the credential.
    static func postForm(_ url: URL, fields: KeyValuePairs<String, String>) -> HTTPRequest {
        let encoded = fields.map { name, value in encodeQueryValue(name) + "=" + encodeQueryValue(value) }
        return HTTPRequest(
            url: url,
            method: "POST",
            bearerToken: nil,
            body: Data(encoded.joined(separator: "&").utf8),
            contentType: "application/x-www-form-urlencoded"
        )
    }
}

/// Status and body of an HTTP response; the body of an error response is kept too.
internal struct HTTPResponse: Sendable {
    var status: Int
    var body: Data

    var isSuccessful: Bool { (200..<300).contains(status) }
}

/// HTTP over one `URLSession` that never follows redirects and caps response bodies.
///
/// Network failures surface as `URLError`; every HTTP status, errors and redirects included, comes back as an
/// `HTTPResponse`.
internal final class HTTPTransport: Sendable {
    /// The same cap as for WebSocket messages, so a broken or hostile server cannot exhaust memory. A widget
    /// extension on iOS has far less memory than an app, so it pays to stop early.
    static let maxResponseBytes = 64 * 1024 * 1024

    /// A request may take this long. History queries on a slow Raspberry Pi can take several seconds.
    static let requestTimeout: TimeInterval = 12

    /// The whole exchange, body included, may take this long.
    static let resourceTimeout: TimeInterval = 40

    let session: URLSession
    private let maxResponseBytes: Int

    /// - Parameter configuration: tests pass one with a stub `URLProtocol`; otherwise leave it out.
    init(configuration: URLSessionConfiguration = .ephemeral, maxResponseBytes: Int = HTTPTransport.maxResponseBytes) {
        configuration.timeoutIntervalForRequest = Self.requestTimeout
        configuration.timeoutIntervalForResource = Self.resourceTimeout
        configuration.waitsForConnectivity = false
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        session = URLSession(configuration: configuration, delegate: RedirectRefusal(), delegateQueue: nil)
        self.maxResponseBytes = maxResponseBytes
    }

    deinit {
        session.invalidateAndCancel()
    }

    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body
        urlRequest.setValue("application/json, image/*", forHTTPHeaderField: "Accept")
        if let contentType = request.contentType {
            urlRequest.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if let bearerToken = request.bearerToken {
            urlRequest.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }

        let (bytes, response) = try await session.bytes(for: urlRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        let status = httpResponse.statusCode
        // Refuse early when the server announces too much, and keep counting in case it lied or sent no length.
        if httpResponse.expectedContentLength > Int64(maxResponseBytes) {
            bytes.task.cancel()
            throw tooLarge(status)
        }
        var body = Data()
        body.reserveCapacity(max(0, Int(min(httpResponse.expectedContentLength, 1024 * 1024))))
        for try await byte in bytes {
            guard body.count < maxResponseBytes else {
                bytes.task.cancel()
                throw tooLarge(status)
            }
            body.append(byte)
        }
        return HTTPResponse(status: status, body: body)
    }

    private func tooLarge(_ status: Int) -> HAError {
        HAError("Home Assistant sent more than \(maxResponseBytes) bytes", httpStatus: status)
    }
}

/// Refuses every redirect, so the 3xx itself comes back as the response.
///
/// Home Assistant does not redirect its API, and following a redirect would carry the bearer token to whatever
/// host the `Location` header names.
private final class RedirectRefusal: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest
    ) async -> URLRequest? {
        nil
    }
}

/// Characters that never need escaping anywhere in a URL (RFC 3986 "unreserved").
private let unreservedCharacters: CharacterSet = {
    var allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
    allowed.insert(charactersIn: "-._~")
    return allowed
}()

/// Percent-encodes one path segment, such as an entity id or a date. Everything outside the unreserved set is
/// escaped, so a `/`, `?` or `#` in the value cannot change which endpoint is called.
internal func encodePathSegment(_ value: String) -> String {
    // Only fails for unpaired surrogates, which a Swift String cannot hold.
    value.addingPercentEncoding(withAllowedCharacters: unreservedCharacters) ?? ""
}

/// Percent-encodes a query or form value the same strict way, so `&`, `=` and `+` stay literal.
internal func encodeQueryValue(_ value: String) -> String {
    encodePathSegment(value)
}
