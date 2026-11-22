import Foundation
import Testing
@testable import CozyHomeHAClient

@Suite("WebSocket messages")
struct MessageTests {
    @Test func singleMessageIsParsed() throws {
        let messages = HAMessages.parse(#"{"id": 3, "type": "result", "success": true, "result": {"version": "2026.11"}}"#)

        let message = try #require(messages.first)
        #expect(messages.count == 1)
        #expect(message.isResult)
        #expect(message.id == 3)
        #expect(message.success)
        #expect(message.result?["version"] == "2026.11")
    }

    @Test func coalescedArrayGivesEveryMessageInOrder() {
        let messages = HAMessages.parse("""
            [{"id": 4, "type": "event", "event": {"event_type": "state_changed"}},
             {"id": 5, "type": "result", "success": false, "error": {"code": "not_found", "message": "Entity not found"}}]
            """)

        #expect(messages.map(\.type) == ["event", "result"])
        #expect(messages.map(\.id) == [4, 5])
        #expect(messages[1].success == false)
        #expect(messages[1].errorCode == "not_found")
        #expect(messages[1].errorMessage == "Entity not found")
    }

    @Test func authMessagesAreRecognized() {
        #expect(HAMessages.parse(#"{"type": "auth_required", "ha_version": "2026.11.2"}"#).first?.isAuthRequired == true)
        #expect(HAMessages.parse(#"{"type": "auth_required", "ha_version": "2026.11.2"}"#).first?.haVersion == "2026.11.2")
        #expect(HAMessages.parse(#"{"type": "auth_ok"}"#).first?.isAuthOK == true)
        let invalid = HAMessages.parse(#"{"type": "auth_invalid", "message": "Invalid password"}"#).first
        #expect(invalid?.isAuthInvalid == true)
        #expect(invalid?.errorMessage == "Invalid password")
    }

    @Test(arguments: ["", "not json", "{\"type\": ", "\u{FEFF}"])
    func textThatIsNotJSONGivesNoMessages(text: String) {
        #expect(HAMessages.parse(text).isEmpty)
    }

    @Test func missingMembersFallBackToSafeDefaults() throws {
        let message = try #require(HAMessages.parse(#"{"id": "7"}"#).first)

        #expect(message.type == "")
        #expect(message.id == 7)
        #expect(message.success == false)
        #expect(message.result == nil)
    }

    @Test func commandCarriesItsIDTypeAndFields() throws {
        let command = HACommand("frontend/get_system_data", fields: ["key": "home"])

        let text = try HAMessages.command(id: 12, command)

        #expect(try JSONValue(parsing: Data(text.utf8)) == ["id": 12, "type": "frontend/get_system_data", "key": "home"])
    }

    @Test func authMessageCarriesTheToken() throws {
        let text = try HAMessages.auth(accessToken: "token \"with\" quotes")

        #expect(try JSONValue(parsing: Data(text.utf8)) == ["type": "auth", "access_token": "token \"with\" quotes"])
    }
}
