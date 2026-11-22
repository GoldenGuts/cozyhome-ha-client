import Foundation

/// One authenticated connection to Home Assistant's
/// [WebSocket API](https://developers.home-assistant.io/docs/api/websocket).
///
/// Commands run one after another: `call` sends a command and reads until its result arrives. Callers that arrive
/// while a command is in flight wait their turn, in order, so one command's read loop never swallows another's
/// result. Get a session from `HAWebSocketClient.withSession`.
public actor HASession {
    /// How long the server may stay silent while a command waits for its result.
    internal static let commandTimeout: Duration = .seconds(30)

    /// How long each step of the login may take. Shorter than a command: the server answers it at once.
    internal static let authTimeout: Duration = .seconds(15)

    /// The Home Assistant version the server announced, when it did.
    public nonisolated let haVersion: String?

    private let channel: any WebSocketChannel
    private var nextCommandID = 1
    private var isCommandInFlight = false
    private var callersWaiting: [CheckedContinuation<Void, Never>] = []

    private init(channel: any WebSocketChannel, haVersion: String?) {
        self.channel = channel
        self.haVersion = haVersion
    }

    /// Sends `command` and waits for its result.
    ///
    /// - Returns: the `result` member of the answer, or nil when it has none.
    /// - Throws: `HAError` when Home Assistant answers with an error; `URLError` when the connection fails, closes
    ///   or stays silent for `commandTimeout`.
    public func call(_ command: HACommand) async throws -> JSONValue? {
        await waitForTurn()
        defer { passTurn() }
        let commandID = nextCommandID
        nextCommandID += 1
        try await channel.send(HAMessages.command(id: commandID, command))
        while true {
            let text = try await channel.receive(timeout: Self.commandTimeout)
            for message in HAMessages.parse(text) where message.isResult && message.id == commandID {
                guard message.success else {
                    let detail = [message.errorCode, message.errorMessage].compactMap { part in part }
                    throw HAError("\(command.type): \(detail.joined(separator: " "))")
                }
                return message.result
            }
        }
    }

    /// Like `call`, but returns nil when Home Assistant answers with an error, such as `unknown_command` from a
    /// version that predates the command. Network errors are still thrown.
    public func tryCall(_ command: HACommand) async throws -> JSONValue? {
        do {
            return try await call(command)
        } catch is HAError {
            return nil
        }
    }

    // MARK: - Taking turns

    private func waitForTurn() async {
        guard isCommandInFlight else {
            isCommandInFlight = true
            return
        }
        await withCheckedContinuation { continuation in
            callersWaiting.append(continuation)
        }
        // The caller that finished handed its turn over without clearing isCommandInFlight.
    }

    private func passTurn() {
        if callersWaiting.isEmpty {
            isCommandInFlight = false
        } else {
            callersWaiting.removeFirst().resume()
        }
    }

    // MARK: - Connecting

    /// Runs the [authentication phase](https://developers.home-assistant.io/docs/api/websocket#authentication-phase)
    /// on a freshly opened channel. Closes the channel when the login fails.
    ///
    /// - Throws: `HAError` with status 401 when Home Assistant rejects the token; `URLError` when the connection
    ///   fails or closes first.
    internal static func authenticate(
        channel: any WebSocketChannel,
        baseURL: String,
        tokenManager: TokenManager
    ) async throws -> HASession {
        do {
            var announcedVersion: String?
            while true {
                for message in HAMessages.parse(try await channel.receive(timeout: authTimeout)) {
                    if message.isAuthRequired {
                        announcedVersion = message.haVersion
                        let accessToken = try await tokenManager.accessToken(for: baseURL)
                        try await channel.send(HAMessages.auth(accessToken: accessToken))
                    } else if message.isAuthOK {
                        return HASession(channel: channel, haVersion: message.haVersion ?? announcedVersion)
                    } else if message.isAuthInvalid {
                        let reason = message.errorMessage ?? ""
                        throw HAError("Home Assistant rejected the login: \(reason)", httpStatus: httpUnauthorized)
                    }
                }
            }
        } catch {
            channel.close()
            throw error
        }
    }

    internal func close() {
        channel.close()
    }
}

/// Opens `HASession`s against the configured URLs.
public struct HAWebSocketClient: Sendable {
    private let tokenStore: any TokenStore
    private let tokenManager: TokenManager
    private let openChannel: @Sendable (URL) -> any WebSocketChannel

    /// - Parameter tokenManager: the same manager the REST client uses, so both share one refresh.
    public init(tokenStore: any TokenStore, tokenManager: TokenManager) {
        let session = tokenManager.transport.session
        self.init(tokenStore: tokenStore, tokenManager: tokenManager) { url in
            URLSessionWebSocketChannel(url: url, session: session)
        }
    }

    internal init(
        tokenStore: any TokenStore,
        tokenManager: TokenManager,
        openChannel: @escaping @Sendable (URL) -> any WebSocketChannel
    ) {
        self.tokenStore = tokenStore
        self.tokenManager = tokenManager
        self.openChannel = openChannel
    }

    /// Connects to the first reachable URL, authenticates, runs `body` and closes the connection, also when `body`
    /// throws.
    ///
    /// A network error moves on to the next URL. A rejected login stops at once, since every URL reaches the same
    /// server. Errors thrown by `body` are not retried.
    ///
    /// `body` runs in the caller's isolation (`isolation` is filled in by the compiler), so it can touch main-actor
    /// state directly.
    ///
    /// - Throws: `HAError` when Home Assistant rejects the token; the last `URLError` when no URL is reachable.
    public func withSession<Result>(
        isolation: isolated (any Actor)? = #isolation,
        _ body: (HASession) async throws -> Result
    ) async throws -> Result {
        let session = try await firstReachable(configuredBaseURLs(tokenStore)) { baseURL in
            guard let url = Self.webSocketURL(for: baseURL) else {
                throw URLError(.badURL)
            }
            return try await HASession.authenticate(
                channel: openChannel(url),
                baseURL: baseURL,
                tokenManager: tokenManager
            )
        }
        do {
            let result = try await body(session)
            await session.close()
            return result
        } catch {
            await session.close()
            throw error
        }
    }

    /// `/api/websocket` under `baseURL`, with `http` mapped to `ws` and `https` to `wss`. A base path is kept,
    /// because Home Assistant behind a reverse proxy often lives under one (`https://example.com/ha`).
    ///
    /// The scheme is compared lowercased: an `HTTPS://` URL typed by hand must not fall through to plain `ws`.
    internal static func webSocketURL(for baseURL: String) -> URL? {
        guard var components = URLComponents(string: normalizeBaseURL(baseURL)),
              let host = components.host, !host.isEmpty else {
            return nil
        }
        switch components.scheme?.lowercased() {
        case "https":
            components.scheme = "wss"
        case "http":
            components.scheme = "ws"
        default:
            return nil
        }
        components.percentEncodedPath += "/api/websocket"
        return components.url
    }
}
