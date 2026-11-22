import Foundation
import Security
import Testing
@testable import CozyHomeHAClient

@Suite("Tokens stay out of descriptions")
struct RedactionTests {
    private let tokens = OAuthTokens(
        accessToken: "access-secret",
        accessTokenExpiresAt: Date(timeIntervalSince1970: 1_800_000_000),
        refreshToken: "refresh-secret",
        clientID: "https://ha.example.com/"
    )

    private func expectNoSecrets(_ text: String, sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(!text.contains("access-secret"), sourceLocation: sourceLocation)
        #expect(!text.contains("refresh-secret"), sourceLocation: sourceLocation)
        #expect(!text.contains("long-lived-secret"), sourceLocation: sourceLocation)
    }

    @Test func oAuthTokensHideTheirTokens() {
        var dumped = ""
        dump(tokens, to: &dumped)

        expectNoSecrets(String(describing: tokens))
        expectNoSecrets(String(reflecting: tokens))
        expectNoSecrets("\(tokens)")
        expectNoSecrets(dumped)
        #expect(String(describing: tokens).contains("https://ha.example.com/"))
    }

    @Test func storesHideTheirTokens() {
        let store = InMemoryTokenStore(baseURL: "https://ha.example.com", longLivedToken: "long-lived-secret", oAuthTokens: tokens)

        expectNoSecrets(String(describing: store))
        expectNoSecrets(String(describing: KeychainTokenStore(service: "test")))
        #expect(String(describing: store).contains("long-lived token"))
    }

    @Test func requestsHideTheBearerToken() throws {
        let url = try #require(URL(string: "https://ha.example.com/api/states"))
        let request = HTTPRequest.get(url, bearerToken: "access-secret")

        expectNoSecrets(String(describing: request))
        #expect(String(describing: request) == "HTTPRequest(GET https://ha.example.com/api/states)")
    }

    @Test func tokenGrantHidesItsTokens() {
        let grant = TokenGrant(accessToken: "access-secret", expiresIn: 1800, refreshToken: "refresh-secret")

        expectNoSecrets(String(describing: grant))
    }
}

@Suite("Keychain item")
struct KeychainAttributeTests {
    @Test func itemIsReadableAfterFirstUnlockAndStaysOnThisAccount() throws {
        let store = KeychainTokenStore(service: "in.weenja.cozyhome", accessGroup: "group.in.weenja.cozyhome")

        let query = store.itemQuery()
        let attributes = store.itemAttributes(data: Data())

        #expect(query[kSecClass as String] as? String == kSecClassGenericPassword as String)
        #expect(query[kSecAttrService as String] as? String == "in.weenja.cozyhome")
        #expect(query[kSecAttrAccessGroup as String] as? String == "group.in.weenja.cozyhome")
        #expect(attributes[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlock as String)
        #expect(query[kSecAttrSynchronizable as String] == nil)
        #expect(attributes[kSecAttrSynchronizable as String] == nil)
    }

    @Test func storedCredentialsRoundTrip() throws {
        let credentials = KeychainTokenStore.StoredCredentials(
            longLivedToken: nil,
            oAuthTokens: OAuthTokens(
                accessToken: "a",
                accessTokenExpiresAt: Date(timeIntervalSince1970: 1_800_000_000),
                refreshToken: "r",
                clientID: "https://ha.example.com/"
            )
        )

        let decoded = try JSONDecoder().decode(
            KeychainTokenStore.StoredCredentials.self,
            from: JSONEncoder().encode(credentials)
        )

        #expect(decoded == credentials)
    }
}
