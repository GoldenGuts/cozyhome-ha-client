import Foundation
import Testing
@testable import CozyHomeHAClient

extension StubbedNetworkTests {
    @Suite("REST client")
    struct RestClientTests {
        private let clock = TestClock()

        private func client(
            store: InMemoryTokenStore,
            maxResponseBytes: Int = HTTPTransport.maxResponseBytes
        ) -> HARestClient {
            let transport = StubServer.transport(maxResponseBytes: maxResponseBytes)
            let tokens = TokenManager(tokenStore: store, transport: transport, now: clock.function)
            return HARestClient(tokenStore: store, tokenManager: tokens)
        }

        // MARK: Authorization

        @Test func statesAreFetchedWithTheBearerToken() async throws {
            StubServer.reset { _ in .json(#"[{"entity_id": "light.kitchen", "state": "on"}]"#) }

            let states = try await client(store: Fixture.loggedInStore(clock: clock)).states()

            #expect(states.arrayValue.first?["entity_id"] == "light.kitchen")
            let request = try #require(StubServer.requests.first)
            #expect(request.url.absoluteString == "https://ha.example.com/api/states")
            #expect(request.authorization == "Bearer access-1")
        }

        @Test func unauthorizedIsRetriedOnceWithARefreshedToken() async throws {
            StubServer.reset { request in
                if Fixture.isTokenRequest(request) {
                    return Fixture.tokenResponse(accessToken: "access-2")
                }
                return request.authorization == "Bearer access-2" ? .json("[]") : .http(status: 401)
            }
            let store = Fixture.loggedInStore(clock: clock)

            _ = try await client(store: store).states()

            let sequence = StubServer.requests.map { request in "\(request.method) \(request.url.path) \(request.authorization ?? "-")" }
            #expect(sequence == [
                "GET /api/states Bearer access-1",
                "POST /auth/token -",
                "GET /api/states Bearer access-2",
            ])
            #expect(store.oAuthTokens?.accessToken == "access-2")
        }

        @Test func secondUnauthorizedIsNotRetriedAgain() async throws {
            StubServer.reset { request in
                Fixture.isTokenRequest(request) ? Fixture.tokenResponse(accessToken: "access-2") : .http(status: 401)
            }

            let error = await #expect(throws: HAError.self) {
                try await client(store: Fixture.loggedInStore(clock: clock)).states()
            }

            #expect(error?.httpStatus == 401)
            #expect(StubServer.requests.count == 3)
        }

        @Test func longLivedTokenIsNotRetriedAfterUnauthorized() async throws {
            StubServer.reset { _ in .http(status: 401) }
            let store = InMemoryTokenStore(baseURL: Fixture.primaryURL, longLivedToken: "long-lived")

            await #expect(throws: HAError.self) {
                try await client(store: store).states()
            }

            #expect(StubServer.requests.count == 1)
            #expect(store.longLivedToken == "long-lived")
        }

        // MARK: Fallback URL

        @Test func networkErrorMovesOnToTheFallbackURL() async throws {
            StubServer.reset { request in
                request.url.host == Fixture.primaryHost ? .networkError(.cannotConnectToHost) : .json("[]")
            }

            _ = try await client(store: Fixture.loggedInStore(clock: clock)).states()

            #expect(StubServer.requests.map(\.url.absoluteString) == [
                "https://ha.example.com/api/states",
                "http://192.168.1.10:8123/api/states",
            ])
        }

        @Test func errorAnswerFromHomeAssistantDoesNotTryTheFallback() async throws {
            StubServer.reset { _ in .json(#"{"message": "Service not found."}"#, status: 400) }

            let error = await #expect(throws: HAError.self) {
                try await client(store: Fixture.loggedInStore(clock: clock))
                    .callService(domain: "light", service: "turn_on", data: ["entity_id": "light.kitchen"])
            }

            #expect(error?.httpStatus == 400)
            #expect(StubServer.requests(toHost: Fixture.fallbackHost).isEmpty)
            #expect(StubServer.requests.count == 1)
        }

        @Test func everyURLUnreachableThrowsTheNetworkError() async throws {
            StubServer.reset { _ in .networkError(.timedOut) }

            let error = await #expect(throws: URLError.self) {
                try await client(store: Fixture.loggedInStore(clock: clock)).states()
            }

            #expect(error?.code == .timedOut)
            #expect(StubServer.requests.count == 2)
        }

        // MARK: Images

        @Test(arguments: [
            "https://evil.example/cover.jpg",
            "https://ha.example.com@evil.example/cover.jpg",
            "https://ha.example.com.evil.example/cover.jpg",
            "http://ha.example.com/cover.jpg",
            "https://ha.example.com:8443/cover.jpg",
            "//evil.example/cover.jpg",
            "/\\evil.example/cover.jpg",
            "@evil.example/cover.jpg",
            "ftp://ha.example.com/cover.jpg",
        ])
        func imageOnAnotherOriginIsRefusedWithoutARequest(pathOrURL: String) async throws {
            StubServer.reset { _ in .http(status: 200, body: Data([0xFF, 0xD8])) }

            let image = try await client(store: Fixture.loggedInStore(clock: clock)).image(pathOrURL)

            #expect(image == nil)
            #expect(StubServer.requests.isEmpty)
        }

        @Test func imageOnTheConfiguredOriginIsFetchedWithTheToken() async throws {
            let jpeg = Data([0xFF, 0xD8, 0xFF])
            StubServer.reset { _ in .http(status: 200, body: jpeg, headers: ["Content-Type": "image/jpeg"]) }
            let rest = client(store: Fixture.loggedInStore(clock: clock))

            #expect(try await rest.image("HTTPS://HA.EXAMPLE.COM:443/api/camera_proxy/camera.door") == jpeg)
            #expect(try await rest.image("/api/media_player_proxy/media_player.tv?token=x") == jpeg)

            #expect(StubServer.requests.map { request in request.url.host?.lowercased() } == [
                Fixture.primaryHost,
                Fixture.primaryHost,
            ])
            #expect(StubServer.requests.allSatisfy { request in request.authorization == "Bearer access-1" })
        }

        @Test func imageOnTheFallbackOriginIsFetchedThere() async throws {
            StubServer.reset { _ in .http(status: 200, body: Data([1])) }

            _ = try await client(store: Fixture.loggedInStore(clock: clock)).image("http://192.168.1.10:8123/local/a.png")

            #expect(StubServer.requests.map(\.url.absoluteString) == ["http://192.168.1.10:8123/local/a.png"])
        }

        // MARK: Redirects and size

        @Test func redirectIsRefusedAndTheTokenStaysHome() async throws {
            StubServer.reset { request in
                request.url.host == Fixture.primaryHost
                    ? .redirect(status: 302, location: "https://evil.example/collect")
                    : .json("[]")
            }

            let error = await #expect(throws: HAError.self) {
                try await client(store: Fixture.loggedInStore(clock: clock)).states()
            }

            #expect(error?.httpStatus == 302)
            #expect(StubServer.requests(toHost: "evil.example").isEmpty)
            #expect(StubServer.requests.count == 1)
        }

        @Test func oversizedResponseIsRefused() async throws {
            StubServer.reset { _ in .http(status: 200, body: Data(repeating: 0x20, count: 2048)) }

            let error = await #expect(throws: HAError.self) {
                try await client(store: Fixture.loggedInStore(clock: clock), maxResponseBytes: 1024).states()
            }

            #expect(error?.message.contains("more than 1024 bytes") == true)
        }

        // MARK: URL encoding

        @Test func serviceNamesArePercentEncodedAsPathSegments() async throws {
            StubServer.reset { _ in .json(#"{"service_response": {"weather.home": {"forecast": []}}}"#) }
            let rest = client(store: Fixture.loggedInStore(clock: clock))

            let response = try await rest.callServiceWithResponse(
                domain: "weather",
                service: "get_forecasts/../../config",
                data: ["entity_id": "weather.home", "type": "daily"]
            )

            #expect(response?["weather.home"]?["forecast"] == [])
            let request = try #require(StubServer.requests.first)
            #expect(request.url.absoluteString
                == "https://ha.example.com/api/services/weather/get_forecasts%2F..%2F..%2Fconfig?return_response")
            #expect(request.headers["Content-Type"] == "application/json")
            #expect(try JSONValue(parsing: request.body) == ["entity_id": "weather.home", "type": "daily"])
        }

        @Test func historyEncodesTheStartAndEveryEntityID() async throws {
            StubServer.reset { _ in .json("[[]]") }
            let start = Date(timeIntervalSince1970: 1_800_000_000)

            _ = try await client(store: Fixture.loggedInStore(clock: clock))
                .history(entityIDs: ["sensor.living room", "sensor.a&b=c"], since: start)

            let request = try #require(StubServer.requests.first)
            #expect(request.url.absoluteString == "https://ha.example.com/api/history/period/2027-01-15T08%3A00%3A00Z"
                + "?filter_entity_id=sensor.living%20room%2Csensor.a%26b%3Dc&minimal_response&no_attributes")
        }

        @Test func historyOfNoEntitiesMakesNoRequest() async throws {
            StubServer.reset { _ in .json("[]") }

            let history = try await client(store: Fixture.loggedInStore(clock: clock)).history(entityIDs: [], since: Date())

            #expect(history == [])
            #expect(StubServer.requests.isEmpty)
        }

        // MARK: Ping

        @Test func pingAcceptsUnauthorizedAndSendsNoToken() async throws {
            StubServer.reset { _ in .json(#"{"message": "Unauthorized"}"#, status: 401) }

            #expect(await client(store: InMemoryTokenStore()).ping("https://ha.example.com/"))
            #expect(StubServer.requests.first?.authorization == nil)
            #expect(StubServer.requests.first?.url.absoluteString == "https://ha.example.com/api/")
        }

        @Test func pingRefusesOtherAnswers() async throws {
            StubServer.reset { _ in .http(status: 404) }

            #expect(await client(store: InMemoryTokenStore()).ping(Fixture.primaryURL) == false)
            #expect(await client(store: InMemoryTokenStore()).ping("not a url") == false)
        }
    }
}
