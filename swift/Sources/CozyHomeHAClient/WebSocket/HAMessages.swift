import Foundation

/// A Home Assistant WebSocket command without its id
/// ([WebSocket API](https://developers.home-assistant.io/docs/api/websocket)).
///
/// The session assigns the id when it sends the command, so one command can be reused across connections.
public struct HACommand: Sendable, Equatable {
    /// The command type, for example `get_states`.
    public let type: String

    /// The command's other members.
    public let fields: [String: JSONValue]

    public init(_ type: String, fields: [String: JSONValue] = [:]) {
        // Either would overwrite what the session writes and break matching the result to the command.
        precondition(fields["id"] == nil, "fields must not contain \"id\"")
        precondition(fields["type"] == nil, "fields must not contain \"type\"")
        self.type = type
        self.fields = fields
    }
}

/// One message received from Home Assistant's WebSocket API
/// ([message types](https://developers.home-assistant.io/docs/api/websocket#message-format)).
///
/// Only the members this client reads are kept.
public struct HAMessage: Sendable, Equatable {
    /// The `type` member, or an empty string when it is missing.
    public var type: String

    /// The command id a `result` belongs to.
    public var id: Int?

    /// The `success` member of a result; false when missing.
    public var success: Bool

    /// The `result` member of a successful result.
    public var result: JSONValue?

    /// The `error.code` member of a failed result, such as `unknown_command`.
    public var errorCode: String?

    /// The `error.message` member, or the top-level `message` of `auth_invalid`.
    public var errorMessage: String?

    /// The Home Assistant version sent with `auth_required` and `auth_ok`.
    public var haVersion: String?

    /// The server's greeting: it waits for an `auth` message.
    public var isAuthRequired: Bool { type == "auth_required" }

    /// The access token was accepted; commands may follow.
    public var isAuthOK: Bool { type == "auth_ok" }

    /// The access token was rejected; the server closes the connection next.
    public var isAuthInvalid: Bool { type == "auth_invalid" }

    /// The answer to one command, matched by `id`.
    public var isResult: Bool { type == "result" }
}

/// Builds and reads Home Assistant WebSocket messages. No I/O, so the protocol can be tested without a server.
public enum HAMessages {
    /// The first message the client sends, answering `auth_required`
    /// ([authentication phase](https://developers.home-assistant.io/docs/api/websocket#authentication-phase)).
    ///
    /// The text holds the token: send it, never log it.
    public static func auth(accessToken: String) throws -> String {
        try text(of: ["type": "auth", "access_token": .string(accessToken)])
    }

    /// A command message: `{"id": id, "type": type, ...fields}`. Ids must increase within a connection.
    public static func command(id: Int, _ command: HACommand) throws -> String {
        var members = command.fields
        members["id"] = .number(Double(id))
        members["type"] = .string(command.type)
        return try text(of: .object(members))
    }

    /// Parses one incoming WebSocket text message. Home Assistant may coalesce several messages into one JSON
    /// array, so the result is a list.
    ///
    /// - Returns: the messages, or an empty list when `text` is not JSON.
    public static func parse(_ text: String) -> [HAMessage] {
        guard let root = try? JSONValue(parsing: Data(text.utf8)) else { return [] }
        if case .array(let items) = root {
            return items.map(message(from:))
        }
        return [message(from: root)]
    }

    private static func message(from item: JSONValue) -> HAMessage {
        let error = item["error"]
        return HAMessage(
            type: item["type"]?.stringValue ?? "",
            id: item["id"]?.intValue,
            success: item["success"]?.boolValue == true,
            result: item["result"],
            errorCode: error?["code"]?.stringValue,
            errorMessage: error?["message"]?.stringValue ?? item["message"]?.stringValue,
            haVersion: item["ha_version"]?.stringValue
        )
    }

    private static func text(of value: JSONValue) throws -> String {
        String(decoding: try value.encoded(), as: UTF8.self)
    }
}
