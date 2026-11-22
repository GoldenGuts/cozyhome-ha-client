package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.rest.CannedResponse
import weenja.cozyhome.ha.rest.LocalHaServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenManagerTest {
    private val clock = MutableClock(nowMillis = 10_000_000)

    private fun tokensExpiringAt(expiresAtMillis: Long, clientId: String): OAuthTokens {
        return OAuthTokens("old-access", expiresAtMillis, "refresh-1", clientId)
    }

    private val refreshAnswer = """{"access_token":"new-access","expires_in":1800,"token_type":"Bearer"}"""

    @Test
    fun `long-lived token wins and is never refreshed`() {
        LocalHaServer { CannedResponse.json(200, refreshAnswer) }.use { server ->
            val store = InMemoryTokenStore(
                baseUrl = server.baseUrl,
                longLivedToken = "long-lived",
                oAuthTokens = tokensExpiringAt(0, server.baseUrl + "/"),
            )
            val manager = TokenManager(store, clock)

            assertEquals("long-lived", manager.accessToken(server.baseUrl, forceRefresh = true))
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `access token is reused while more than a minute is left`() {
        LocalHaServer { CannedResponse.json(200, refreshAnswer) }.use { server ->
            val expiresAt = clock.nowMillis + TokenManager.REFRESH_MARGIN_MILLIS + 1
            val store = InMemoryTokenStore(server.baseUrl, oAuthTokens = tokensExpiringAt(expiresAt, "client"))
            val manager = TokenManager(store, clock)

            assertEquals("old-access", manager.accessToken(server.baseUrl, forceRefresh = false))
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `access token is refreshed within a minute of expiry`() {
        LocalHaServer { CannedResponse.json(200, refreshAnswer) }.use { server ->
            val clientId = server.baseUrl + "/"
            val expiresAt = clock.nowMillis + TokenManager.REFRESH_MARGIN_MILLIS - 1
            val store = InMemoryTokenStore(server.baseUrl, oAuthTokens = tokensExpiringAt(expiresAt, clientId))
            val manager = TokenManager(store, clock)

            assertEquals("new-access", manager.accessToken(server.baseUrl, forceRefresh = false))

            val request = server.requests.single()
            assertEquals("/auth/token", request.rawPath)
            val expectedForm = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "refresh-1",
                "client_id" to clientId,
            )
            assertEquals(expectedForm, decodeForm(request.body))
            val expectedTokens = OAuthTokens("new-access", clock.nowMillis + 1_800_000, "refresh-1", clientId)
            assertEquals(expectedTokens, store.oAuthTokens)
        }
    }

    @Test
    fun `forced refresh ignores a token that has not expired`() {
        LocalHaServer { CannedResponse.json(200, refreshAnswer) }.use { server ->
            val expiresAt = clock.nowMillis + 3_600_000
            val store = InMemoryTokenStore(server.baseUrl, oAuthTokens = tokensExpiringAt(expiresAt, "client"))
            val manager = TokenManager(store, clock)

            assertEquals("new-access", manager.accessToken(server.baseUrl, forceRefresh = true))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `revoked refresh token clears the login and asks for a new one`() {
        LocalHaServer { CannedResponse.json(400, """{"error":"invalid_grant"}""") }.use { server ->
            val store = InMemoryTokenStore(
                baseUrl = server.baseUrl,
                fallbackUrl = "http://192.168.1.10:8123",
                oAuthTokens = tokensExpiringAt(0, "client"),
            )
            val manager = TokenManager(store, clock)

            val error = assertFailsWith<HaException> { manager.accessToken(server.baseUrl, forceRefresh = false) }

            assertEquals(401, error.httpStatus)
            assertNull(store.oAuthTokens)
            assertEquals(server.baseUrl, store.baseUrl)
            assertEquals("http://192.168.1.10:8123", store.fallbackUrl)
        }
    }

    @Test
    fun `server error on refresh keeps the login`() {
        LocalHaServer { CannedResponse(500) }.use { server ->
            val tokens = tokensExpiringAt(0, "client")
            val store = InMemoryTokenStore(server.baseUrl, oAuthTokens = tokens)
            val manager = TokenManager(store, clock)

            val error = assertFailsWith<HaException> { manager.accessToken(server.baseUrl, forceRefresh = false) }

            assertEquals(500, error.httpStatus)
            assertEquals(tokens, store.oAuthTokens)
        }
    }

    @Test
    fun `no login raises HaException 401`() {
        val manager = TokenManager(InMemoryTokenStore("https://ha.example.com"), clock)

        val error = assertFailsWith<HaException> { manager.accessToken("https://ha.example.com", forceRefresh = false) }

        assertEquals(401, error.httpStatus)
    }
}
