import Foundation
import Testing
@testable import CozyHomeHAClient

extension StubbedNetworkTests {
    @Suite("Token refresh")
    struct TokenManagerTests {
        private let clock = TestClock()

        private func manager(for store: InMemoryTokenStore) -> TokenManager {
            TokenManager(tokenStore: store, transport: StubServer.transport(), now: clock.function)
        }

        @Test func freshAccessTokenIsReusedWithoutARequest() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "unexpected") }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 61)

            let token = try await manager(for: store).accessToken(for: Fixture.primaryURL)

            #expect(token == "access-1")
            #expect(StubServer.requests.isEmpty)
        }

        @Test func tokenIsRefreshedWithinSixtySecondsOfExpiry() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-2", expiresIn: 1800) }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 59)

            let token = try await manager(for: store).accessToken(for: Fixture.primaryURL)

            #expect(token == "access-2")
            let request = try #require(StubServer.requests.first)
            #expect(StubServer.requests.count == 1)
            #expect(request.url.absoluteString == "https://ha.example.com/auth/token")
            #expect(request.authorization == nil)
            #expect(request.formFields == [
                "grant_type": "refresh_token",
                "refresh_token": "refresh-secret",
                "client_id": Fixture.clientID,
            ])
            let saved = try #require(store.oAuthTokens)
            #expect(saved.accessToken == "access-2")
            #expect(saved.accessTokenExpiresAt == clock.now.addingTimeInterval(1800))
            #expect(saved.refreshToken == "refresh-secret")
        }

        @Test func expiryIsMeasuredWithTheInjectedClock() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-2") }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 1800)
            let tokens = manager(for: store)

            #expect(try await tokens.accessToken(for: Fixture.primaryURL) == "access-1")
            clock.advance(by: 1741)
            #expect(try await tokens.accessToken(for: Fixture.primaryURL) == "access-2")
            #expect(StubServer.requests.count == 1)
        }

        @Test func revokedRefreshTokenClearsTheLogin() async throws {
            StubServer.reset { _ in .json(#"{"error": "invalid_grant"}"#, status: 400) }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 0)

            let error = await #expect(throws: HAError.self) {
                try await manager(for: store).accessToken(for: Fixture.primaryURL)
            }

            #expect(error?.httpStatus == 401)
            #expect(store.oAuthTokens == nil)
            #expect(store.baseURL == Fixture.primaryURL)
        }

        @Test func serverErrorDuringRefreshKeepsTheLogin() async throws {
            StubServer.reset { _ in .http(status: 502) }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 0)

            let error = await #expect(throws: HAError.self) {
                try await manager(for: store).accessToken(for: Fixture.primaryURL)
            }

            #expect(error?.httpStatus == 502)
            #expect(store.oAuthTokens != nil)
        }

        @Test func parallelCallersShareOneRefresh() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "access-2") }
            let store = Fixture.loggedInStore(clock: clock, expiresIn: 0)
            let tokens = manager(for: store)

            let results = try await withThrowingTaskGroup(of: String.self) { group in
                for _ in 0..<8 {
                    group.addTask { try await tokens.accessToken(for: Fixture.primaryURL) }
                }
                return try await group.reduce(into: [String]()) { collected, token in collected.append(token) }
            }

            #expect(results == Array(repeating: "access-2", count: 8))
            #expect(StubServer.requests.count == 1)
        }

        @Test func rejectedTokenAlreadyReplacedIsNotRefreshedAgain() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "unexpected") }
            let store = Fixture.loggedInStore(clock: clock)
            store.oAuthTokens?.accessToken = "access-2"

            let token = try await manager(for: store).accessToken(for: Fixture.primaryURL, replacing: "access-1")

            #expect(token == "access-2")
            #expect(StubServer.requests.isEmpty)
        }

        @Test func longLivedTokenIsUsedAsIs() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "unexpected") }
            let store = InMemoryTokenStore(baseURL: Fixture.primaryURL, longLivedToken: "  long-lived  ")

            let token = try await manager(for: store).accessToken(for: Fixture.primaryURL, replacing: "long-lived")

            #expect(token == "long-lived")
            #expect(StubServer.requests.isEmpty)
        }

        @Test func noLoginIsAnUnauthorizedError() async throws {
            StubServer.reset { _ in Fixture.tokenResponse(accessToken: "unexpected") }

            let error = await #expect(throws: HAError.self) {
                try await manager(for: InMemoryTokenStore(baseURL: Fixture.primaryURL)).accessToken(for: Fixture.primaryURL)
            }

            #expect(error?.httpStatus == 401)
        }
    }
}
