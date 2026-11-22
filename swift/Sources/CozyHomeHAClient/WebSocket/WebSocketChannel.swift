import Foundation

/// A text WebSocket connection, as `HASession` needs it.
///
/// A protocol so the session's handshake and command logic can be tested against a scripted server:
/// `URLProtocol` can stub HTTP, but not a `URLSessionWebSocketTask`.
internal protocol WebSocketChannel: Sendable {
    func send(_ text: String) async throws

    /// The next text message.
    ///
    /// - Throws: `URLError(.timedOut)` when nothing arrives within `timeout`, so a silent server cannot hang a widget
    ///   refresh; another `URLError` when the connection fails or closes.
    func receive(timeout: Duration) async throws -> String

    func close()
}

/// `WebSocketChannel` on `URLSessionWebSocketTask`, which brings the platform's TLS and proxy handling.
internal final class URLSessionWebSocketChannel: WebSocketChannel {
    /// The same cap as for REST responses: Home Assistant's largest answers (the entity registry of a big install)
    /// stay well under it, and a broken server cannot exhaust memory.
    static let maxMessageBytes = HTTPTransport.maxResponseBytes

    private let task: URLSessionWebSocketTask

    /// Opens the connection on the transport's session, so the socket shares its configuration: no cookies, no
    /// cache, the same timeouts.
    init(url: URL, session: URLSession) {
        task = session.webSocketTask(with: url)
        task.maximumMessageSize = Self.maxMessageBytes
        task.resume()
    }

    func send(_ text: String) async throws {
        do {
            try await task.send(.string(text))
        } catch {
            throw Self.networkError(error)
        }
    }

    func receive(timeout: Duration) async throws -> String {
        let socket = task
        let timedOut = Locked(false)
        // URLSessionWebSocketTask.receive() has no timeout of its own. Closing the socket is the only way to end the
        // wait, and it is also what should happen to a connection that went silent.
        let watchdog = Task {
            try await Task.sleep(for: timeout)
            timedOut.withLock { flag in flag = true }
            socket.cancel(with: .goingAway, reason: nil)
        }
        defer { watchdog.cancel() }
        let message: URLSessionWebSocketTask.Message
        do {
            message = try await withTaskCancellationHandler {
                try await socket.receive()
            } onCancel: {
                socket.cancel(with: .goingAway, reason: nil)
            }
        } catch {
            throw timedOut.current ? URLError(.timedOut) : Self.networkError(error)
        }
        switch message {
        case .string(let text):
            return text
        case .data(let data):
            return String(decoding: data, as: UTF8.self)
        @unknown default:
            throw URLError(.cannotParseResponse)
        }
    }

    func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }

    /// Every failure of the socket is a network failure, whatever domain the system reports it in (POSIX errors
    /// show up here too), so the session can move on to the fallback URL.
    private static func networkError(_ error: any Error) -> URLError {
        if let urlError = error as? URLError {
            return urlError
        }
        return URLError(.networkConnectionLost, userInfo: [NSUnderlyingErrorKey: error])
    }
}
