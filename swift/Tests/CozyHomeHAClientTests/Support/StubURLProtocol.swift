import Foundation
@testable import CozyHomeHAClient

/// What the stub answers to one request.
enum StubAnswer: Sendable {
    case http(status: Int, body: Data = Data(), headers: [String: String] = [:])
    case redirect(status: Int, location: String)
    case networkError(URLError.Code)

    static func json(_ text: String, status: Int = 200) -> StubAnswer {
        .http(status: status, body: Data(text.utf8), headers: ["Content-Type": "application/json"])
    }
}

/// A request as the stub saw it, with the body read back from its stream.
struct RecordedRequest: Sendable {
    var method: String
    var url: URL
    var headers: [String: String]
    var body: Data

    var authorization: String? { headers["Authorization"] }
    var bodyText: String { String(decoding: body, as: UTF8.self) }

    /// The form fields of an `application/x-www-form-urlencoded` body, decoded.
    var formFields: [String: String] {
        var fields: [String: String] = [:]
        for pair in bodyText.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            fields[parts[0].removingPercentEncoding ?? parts[0]] = parts[1].removingPercentEncoding ?? parts[1]
        }
        return fields
    }
}

/// Answers every request made through a stubbed transport, and records it. No request reaches the network.
///
/// `URLProtocol` is registered by class, so the routes are process-wide; the suites that use it run serialized
/// (see `StubbedNetworkTests`).
enum StubServer {
    private struct State: Sendable {
        var route: @Sendable (RecordedRequest) -> StubAnswer = { _ in .networkError(.cannotConnectToHost) }
        var requests: [RecordedRequest] = []
    }

    private static let state = Locked(State())

    /// Replaces the routes and forgets earlier requests.
    static func reset(route: @escaping @Sendable (RecordedRequest) -> StubAnswer) {
        state.withLock { current in current = State(route: route, requests: []) }
    }

    static var requests: [RecordedRequest] { state.current.requests }

    static func requests(toHost host: String) -> [RecordedRequest] {
        requests.filter { request in request.url.host == host }
    }

    fileprivate static func answer(_ request: RecordedRequest) -> StubAnswer {
        state.withLock { current in
            current.requests.append(request)
            return current.route(request)
        }
    }

    /// A transport whose session only knows the stub protocol.
    static func transport(maxResponseBytes: Int = HTTPTransport.maxResponseBytes) -> HTTPTransport {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return HTTPTransport(configuration: configuration, maxResponseBytes: maxResponseBytes)
    }
}

final class StubURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let client else { return }
        let recorded = RecordedRequest(
            method: request.httpMethod ?? "GET",
            url: url,
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody ?? Self.readAll(request.httpBodyStream)
        )
        switch StubServer.answer(recorded) {
        case .http(let status, let body, let headers):
            var allHeaders = headers
            allHeaders["Content-Length"] = String(body.count)
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: allHeaders)
            client.urlProtocol(self, didReceive: response!, cacheStoragePolicy: .notAllowed)
            client.urlProtocol(self, didLoad: body)
            client.urlProtocolDidFinishLoading(self)
        case .redirect(let status, let location):
            let headers = ["Location": location, "Content-Length": "0"]
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            // Offer the redirect the way the system's HTTP loader does. If the session's delegate accepts it, the
            // loading system starts a new request to `location`, which the stub would record.
            client.urlProtocol(self, wasRedirectedTo: URLRequest(url: URL(string: location)!), redirectResponse: response)
            client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client.urlProtocolDidFinishLoading(self)
        case .networkError(let code):
            client.urlProtocol(self, didFailWithError: URLError(code))
        }
    }

    override func stopLoading() {}

    private static func readAll(_ stream: InputStream?) -> Data {
        guard let stream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

/// A clock the tests move by hand.
final class TestClock: Sendable {
    private let date: Locked<Date>

    init(_ start: Date = Date(timeIntervalSince1970: 1_800_000_000)) {
        date = Locked(start)
    }

    var now: Date { date.current }

    func advance(by interval: TimeInterval) {
        date.withLock { current in current += interval }
    }

    var function: @Sendable () -> Date {
        { [date] in date.current }
    }
}
