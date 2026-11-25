import Foundation
import Testing
@testable import CozyHomeHAClient

@Suite("OAuth login URLs")
struct OAuthLoginURLTests {
    private let login = OAuthLogin(tokenStore: InMemoryTokenStore())

    @Test func authorizeURLUsesTheHomeAssistantURLAsClientID() throws {
        let url = try #require(login.authorizeURL(for: "https://ha.example.com/"))
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let query = Dictionary(uniqueKeysWithValues: items.map { item in (item.name, item.value ?? "") })

        #expect(url.host == "ha.example.com")
        #expect(url.path == "/auth/authorize")
        #expect(query["response_type"] == "code")
        #expect(query["client_id"] == "https://ha.example.com/")
        #expect(query["redirect_uri"] == "https://ha.example.com/cozyhome/callback")
        #expect(query["state"] != nil)
    }

    @Test func redirectMatchesTheWholeURLNotAPrefix() throws {
        let base = "https://ha.example.com"
        #expect(login.isRedirect(try #require(URL(string: base + "/cozyhome/callback?code=abc&state=x")), for: base))
        #expect(!login.isRedirect(try #require(URL(string: base + "/cozyhome/callback-other?code=abc")), for: base))
        #expect(!login.isRedirect(try #require(URL(string: "https://evil.example/cozyhome/callback?code=abc")), for: base))
    }

    @Test func redirectIsMatchedAfterTheLoginPageNormalisesTheURL() throws {
        func isRedirect(_ url: String, _ base: String) throws -> Bool {
            login.isRedirect(try #require(URL(string: url + "/cozyhome/callback?code=abc")), for: base)
        }
        #expect(try isRedirect("http://homeassistant.local:8123", "http://HomeAssistant.local:8123"))
        #expect(try isRedirect("https://ha.example.com", "https://ha.example.com:443"))
        #expect(try isRedirect("https://ha.example.com/base", "https://ha.example.com/base/"))
        #expect(try !isRedirect("http://ha.example.com", "https://ha.example.com"))
        #expect(try !isRedirect("https://ha.example.com:8443", "https://ha.example.com"))
        #expect(try !isRedirect("https://ha.example.com@evil.example", "https://ha.example.com"))
    }

    @Test func browserLoginUsesTheClientPageAndItsOwnState() throws {
        let client = BrowserClient(clientID: "https://example.com/login", redirectURI: "exampleapp://auth-callback", state: "s1")
        let url = try #require(login.authorizeURL(for: "https://ha.example.com/", client: client))
        #expect(url.absoluteString == "https://ha.example.com/auth/authorize?response_type=code"
            + "&client_id=https%3A%2F%2Fexample.com%2Flogin&redirect_uri=exampleapp%3A%2F%2Fauth-callback&state=s1")

        func code(_ callback: String) throws -> String? {
            login.authorizationCode(from: try #require(URL(string: callback)), client: client)
        }
        #expect(try code("exampleapp://auth-callback?code=abc&state=s1") == "abc")
        #expect(try code("exampleapp://auth-callback?code=abc&state=other") == nil)
        #expect(try code("exampleapp://auth-callback?code=abc") == nil)
        #expect(try code("exampleapp://auth-callback?state=s1") == nil)
        #expect(try code("otherapp://auth-callback?code=abc&state=s1") == nil)
    }

    @Test func eachBrowserLoginHasANewState() {
        let first = BrowserClient(clientID: "https://example.com/login", redirectURI: "exampleapp://auth-callback")
        let second = BrowserClient(clientID: "https://example.com/login", redirectURI: "exampleapp://auth-callback")
        #expect(first.state != second.state)
    }

    @Test func authorizationCodeIsReadAndDecoded() throws {
        let redirect = try #require(URL(string: "https://ha.example.com/cozyhome/callback?state=cozyhome&code=a%2Bb%3D"))
        #expect(login.authorizationCode(from: redirect) == "a+b=")

        let cancelled = try #require(URL(string: "https://ha.example.com/cozyhome/callback?state=cozyhome"))
        #expect(login.authorizationCode(from: cancelled) == nil)
    }
}

extension StubbedNetworkTests {
    @Suite("OAuth code exchange")
    struct CodeExchangeTests {
        @Test func exchangeStoresTheTokensAndThePrimaryURL() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-1", expiresIn: 1800, refreshToken: "refresh-1") }
            let clock = TestClock()
            let store = InMemoryTokenStore(longLivedToken: "old-long-lived")
            let login = OAuthLogin(tokenStore: store, transport: StubServer.transport(), now: clock.function)

            try await login.exchangeCode("code with spaces&more", baseURL: "https://ha.example.com/")

            let request = try #require(StubServer.requests.first)
            #expect(StubServer.requests.count == 1)
            #expect(request.url.absoluteString == "https://ha.example.com/auth/token")
            #expect(request.headers["Content-Type"] == "application/x-www-form-urlencoded")
            #expect(request.authorization == nil)
            #expect(request.formFields == [
                "grant_type": "authorization_code",
                "code": "code with spaces&more",
                "client_id": "https://ha.example.com/",
            ])
            #expect(store.baseURL == "https://ha.example.com")
            #expect(store.longLivedToken == nil)
            #expect(store.oAuthTokens == OAuthTokens(
                accessToken: "access-1",
                accessTokenExpiresAt: clock.now.addingTimeInterval(1800),
                refreshToken: "refresh-1",
                clientID: "https://ha.example.com/"
            ))
        }

        @Test func browserExchangeSendsAndStoresTheClientPage() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-1", expiresIn: 1800, refreshToken: "refresh-1") }
            let store = InMemoryTokenStore()
            let login = OAuthLogin(tokenStore: store, transport: StubServer.transport(), now: { Date() })

            try await login.exchangeCode("code", baseURL: Fixture.primaryURL, clientID: "https://example.com/login")

            #expect(StubServer.requests.first?.formFields["client_id"] == "https://example.com/login")
            #expect(store.oAuthTokens?.clientID == "https://example.com/login")
        }

        @Test func rejectedCodeStoresNothing() async throws {
            StubServer.reset { _ in .json(#"{"error": "invalid_request"}"#, status: 400) }
            let store = InMemoryTokenStore()
            let login = OAuthLogin(tokenStore: store, transport: StubServer.transport(), now: { Date() })

            let error = await #expect(throws: HAError.self) {
                try await login.exchangeCode("expired", baseURL: Fixture.primaryURL)
            }
            #expect(error?.httpStatus == 400)
            #expect(store.oAuthTokens == nil)
            #expect(store.baseURL.isEmpty)
        }

        @Test func answerWithoutRefreshTokenIsRejected() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-1") }
            let store = InMemoryTokenStore()
            let login = OAuthLogin(tokenStore: store, transport: StubServer.transport(), now: { Date() })

            await #expect(throws: HAError.self) {
                try await login.exchangeCode("code", baseURL: Fixture.primaryURL)
            }
            #expect(store.oAuthTokens == nil)
        }
    }
}
