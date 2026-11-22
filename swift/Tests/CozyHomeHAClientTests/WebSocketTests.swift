import Foundation
import Testing
@testable import CozyHomeHAClient

@Suite("WebSocket session")
struct WebSocketSessionTests {
    private static let authRequired = #"{"type": "auth_required", "ha_version": "2026.11.2"}"#
    private static let authOK = #"{"type": "auth_ok", "ha_version": "2026.11.2"}"#

    private let clock = TestClock()

    /// A client whose URLs open the given channels; a URL without one is unreachable.
    private func client(
        store: InMemoryTokenStore,
        channels: [String: ScriptedChannel]
    ) -> HAWebSocketClient {
        let tokens = TokenManager(tokenStore: store, transport: HTTPTransport(), now: clock.function)
        return HAWebSocketClient(tokenStore: store, tokenManager: tokens) { url in
            channels[url.absoluteString] ?? ScriptedChannel.unreachable()
        }
    }

    /// A server that accepts the login and answers `get_states` with one entity, wrapping the result between an
    /// event and another command's result, as a busy server may.
    private static func homeAssistant() -> ScriptedChannel {
        ScriptedChannel(greeting: [authRequired]) { sent in
            let message = try? JSONValue(parsing: Data(sent.utf8))
            if message?["type"] == "auth" {
                return message?["access_token"] == "access-1"
                    ? [authOK]
                    : [#"{"type": "auth_invalid", "message": "Invalid access token or password"}"#]
            }
            guard let id = commandID(of: sent) else { return [] }
            switch message?["type"]?.stringValue {
            case "get_states":
                return [
                    #"{"id": 99, "type": "event", "event": {"event_type": "state_changed"}}"#,
                    #"[{"id": 98, "type": "result", "success": true, "result": null},"#
                        + #"{"id": \#(id), "type": "result", "success": true, "result": [{"entity_id": "light.kitchen"}]}]"#,
                ]
            default:
                return [#"{"id": \#(id), "type": "result", "success": false, "error": {"code": "unknown_command", "message": "Unknown command."}}"#]
            }
        }
    }

    @Test func authenticatesThenMatchesTheResultByID() async throws {
        let server = Self.homeAssistant()
        let store = Fixture.loggedInStore(clock: clock)
        let websocket = client(store: store, channels: ["wss://ha.example.com/api/websocket": server])

        let (version, states) = try await websocket.withSession { session in
            (session.haVersion, try await session.call(HACommand("get_states")))
        }

        #expect(version == "2026.11.2")
        #expect(states == [["entity_id": "light.kitchen"]])
        let sent = server.sent.compactMap { text in try? JSONValue(parsing: Data(text.utf8)) }
        #expect(sent == [
            ["type": "auth", "access_token": "access-1"],
            ["id": 1, "type": "get_states"],
        ])
        #expect(server.isClosed)
    }

    @Test func authInvalidStopsWithoutTryingTheFallback() async throws {
        let primary = Self.homeAssistant()
        let fallback = Self.homeAssistant()
        let store = Fixture.loggedInStore(clock: clock)
        store.oAuthTokens?.accessToken = "revoked"
        let websocket = client(store: store, channels: [
            "wss://ha.example.com/api/websocket": primary,
            "ws://192.168.1.10:8123/api/websocket": fallback,
        ])

        let error = await #expect(throws: HAError.self) {
            try await websocket.withSession { session in try await session.call(HACommand("get_states")) }
        }

        #expect(error?.httpStatus == 401)
        #expect(error?.message == "Home Assistant rejected the login: Invalid access token or password")
        #expect(primary.isClosed)
        #expect(fallback.sent.isEmpty)
    }

    @Test func unreachablePrimaryMovesOnToTheFallback() async throws {
        let fallback = Self.homeAssistant()
        let websocket = client(store: Fixture.loggedInStore(clock: clock), channels: [
            "ws://192.168.1.10:8123/api/websocket": fallback,
        ])

        let states = try await websocket.withSession { session in try await session.call(HACommand("get_states")) }

        #expect(states?.arrayValue.count == 1)
        #expect(fallback.isClosed)
    }

    @Test func failedResultThrowsAndTryCallReturnsNil() async throws {
        let server = Self.homeAssistant()
        let websocket = client(store: Fixture.loggedInStore(clock: clock), channels: [
            "wss://ha.example.com/api/websocket": server,
        ])

        try await websocket.withSession { session in
            let error = await #expect(throws: HAError.self) {
                try await session.call(HACommand("config/floor_registry/list"))
            }
            #expect(error?.message == "config/floor_registry/list: unknown_command Unknown command.")
            let unknown = try await session.tryCall(HACommand("config/floor_registry/list"))
            #expect(unknown == nil)
        }

        #expect(server.isClosed)
    }

    @Test func sessionIsClosedWhenTheBodyThrows() async throws {
        let server = Self.homeAssistant()
        let websocket = client(store: Fixture.loggedInStore(clock: clock), channels: [
            "wss://ha.example.com/api/websocket": server,
        ])

        await #expect(throws: CancellationError.self) {
            try await websocket.withSession { _ in throw CancellationError() }
        }

        #expect(server.isClosed)
    }

    @Test func silentServerFailsInsteadOfHanging() async throws {
        let silent = ScriptedChannel(greeting: [Self.authRequired]) { _ in [] }
        let websocket = client(store: InMemoryTokenStore(baseURL: Fixture.primaryURL, longLivedToken: "token"), channels: [
            "wss://ha.example.com/api/websocket": silent,
        ])

        let error = await #expect(throws: URLError.self) {
            try await websocket.withSession { _ in }
        }

        #expect(error?.code == .timedOut)
        #expect(silent.isClosed)
    }

    @Test(arguments: [
        ("https://ha.example.com", "wss://ha.example.com/api/websocket"),
        ("HTTPS://ha.example.com/", "wss://ha.example.com/api/websocket"),
        ("http://192.168.1.10:8123", "ws://192.168.1.10:8123/api/websocket"),
        ("https://example.com/ha/", "wss://example.com/ha/api/websocket"),
    ])
    func webSocketURLFollowsTheBaseURL(baseURL: String, expected: String) {
        #expect(HAWebSocketClient.webSocketURL(for: baseURL)?.absoluteString == expected)
    }

    @Test(arguments: ["ftp://ha.example.com", "ha.example.com", "", "http://"])
    func webSocketURLRefusesOtherSchemes(baseURL: String) {
        #expect(HAWebSocketClient.webSocketURL(for: baseURL) == nil)
    }
}
