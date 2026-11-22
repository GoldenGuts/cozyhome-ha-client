import Foundation
@testable import CozyHomeHAClient

/// A WebSocket server played from a script: it greets the client, and answers each sent message with the replies
/// `respond` returns. Receiving with nothing queued fails like a silent socket does, so a test cannot hang.
final class ScriptedChannel: WebSocketChannel {
    private struct State: Sendable {
        var inbox: [String]
        var sent: [String] = []
        var isClosed = false
    }

    private let state: Locked<State>
    private let respond: @Sendable (_ sentText: String) -> [String]

    init(greeting: [String], respond: @escaping @Sendable (_ sentText: String) -> [String]) {
        state = Locked(State(inbox: greeting))
        self.respond = respond
    }

    /// Fails like a URL nothing listens on.
    static func unreachable() -> ScriptedChannel {
        ScriptedChannel(greeting: []) { _ in [] }
    }

    var sent: [String] { state.current.sent }
    var isClosed: Bool { state.current.isClosed }

    func send(_ text: String) async throws {
        let replies = respond(text)
        try state.withLock { current in
            guard !current.isClosed else { throw URLError(.networkConnectionLost) }
            current.sent.append(text)
            current.inbox.append(contentsOf: replies)
        }
    }

    func receive(timeout: Duration) async throws -> String {
        try state.withLock { current in
            guard !current.isClosed, !current.inbox.isEmpty else { throw URLError(.timedOut) }
            return current.inbox.removeFirst()
        }
    }

    func close() {
        state.withLock { current in current.isClosed = true }
    }
}

/// Reads the `id` of a command the client sent, to answer with a matching result.
func commandID(of sentText: String) -> Int? {
    HAMessages.parse(sentText).first?.id
}
