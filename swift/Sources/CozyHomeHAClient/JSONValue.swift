import Foundation

/// A JSON tree that is `Sendable`, unlike the `[String: Any]` that `JSONSerialization` returns, so results can cross
/// from the network code to the UI without copying into model types first.
///
/// Reading is forgiving on purpose: the same attribute can arrive as `21.5` from one integration and as `"21.5"`
/// from another, so the scalar accessors convert instead of failing, and lookups return nil for anything missing.
public enum JSONValue: Sendable, Equatable {
    case null
    case bool(Bool)
    /// Stored as a `Double`, like JavaScript does. Home Assistant sends identifiers as strings, so integers above
    /// 2^53 do not occur in practice.
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    /// The member `key` of an object; nil for a missing member or a non-object.
    public subscript(key: String) -> JSONValue? {
        guard case .object(let members) = self else { return nil }
        return members[key]
    }

    /// The text of a string, or a number or bool written as text.
    public var stringValue: String? {
        switch self {
        case .string(let text):
            return text
        case .number(let number):
            return number.rounded() == number && abs(number) < 1e15 ? String(Int64(number)) : String(number)
        case .bool(let flag):
            return flag ? "true" : "false"
        case .null, .array, .object:
            return nil
        }
    }

    /// A number, or a string that holds one.
    public var doubleValue: Double? {
        switch self {
        case .number(let number):
            return number
        case .string(let text):
            return Double(text.trimmingCharacters(in: .whitespaces))
        case .null, .bool, .array, .object:
            return nil
        }
    }

    /// A whole number that fits an `Int`; nil for fractions.
    public var intValue: Int? {
        guard let number = doubleValue, number.rounded() == number, abs(number) <= Double(Int32.max) else {
            return nil
        }
        return Int(number)
    }

    /// A bool, or the strings `"true"` and `"false"`.
    public var boolValue: Bool? {
        switch self {
        case .bool(let flag):
            return flag
        case .string("true"):
            return true
        case .string("false"):
            return false
        default:
            return nil
        }
    }

    /// The elements of an array; empty for anything else.
    public var arrayValue: [JSONValue] {
        guard case .array(let elements) = self else { return [] }
        return elements
    }

    /// Parses UTF-8 JSON text.
    public init(parsing data: Data) throws {
        self = try JSONDecoder().decode(JSONValue.self, from: data)
    }

    /// The compact JSON text of this value. Keys are sorted, so output is stable for tests and diffs.
    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(self)
    }
}

extension JSONValue: Codable {
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let flag = try? container.decode(Bool.self) {
            self = .bool(flag)
        } else if let number = try? container.decode(Double.self) {
            self = .number(number)
        } else if let text = try? container.decode(String.self) {
            self = .string(text)
        } else if let elements = try? container.decode([JSONValue].self) {
            self = .array(elements)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let flag):
            try container.encode(flag)
        case .number(let number):
            try container.encode(number)
        case .string(let text):
            try container.encode(text)
        case .array(let elements):
            try container.encode(elements)
        case .object(let members):
            try container.encode(members)
        }
    }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(nilLiteral: ()) { self = .null }

    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
}
