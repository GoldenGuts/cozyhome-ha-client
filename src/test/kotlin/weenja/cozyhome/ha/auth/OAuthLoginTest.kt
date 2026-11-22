package weenja.cozyhome.ha.auth

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.rest.CannedResponse
import weenja.cozyhome.ha.rest.LocalHaServer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthLoginTest {
    private val clock = MutableClock(nowMillis = 1_000_000)

    @Test
    fun `authorize URL uses the Home Assistant URL as client id and redirects on the same host`() {
        val login = OAuthLogin(InMemoryTokenStore(), clock)

        val url = login.authorizeUrl("https://ha.example.com/")

        val expected = "https://ha.example.com/auth/authorize?response_type=code" +
            "&client_id=https%3A%2F%2Fha.example.com%2F" +
            "&redirect_uri=https%3A%2F%2Fha.example.com%2Fcozyhome%2Fcallback" +
            "&state=cozyhome"
        assertEquals(expected, url)
    }

    @Test
    fun `redirect is recognised and its code read`() {
        val login = OAuthLogin(InMemoryTokenStore(), clock)
        val base = "https://ha.example.com"

        assertTrue(login.isRedirect(base, "https://ha.example.com/cozyhome/callback?code=abc&state=cozyhome"))
        assertFalse(login.isRedirect(base, "https://ha.example.com/cozyhome/callback.evil.example/"))
        assertFalse(login.isRedirect(base, "https://ha.example.com/auth/authorize?client_id=x"))
        assertEquals("a b+c", login.authorizationCode("$base/cozyhome/callback?state=cozyhome&code=a%20b%2Bc"))
        assertNull(login.authorizationCode("$base/cozyhome/callback?state=cozyhome"))
    }

    @Test
    fun `a malformed code is read as no code`() {
        val login = OAuthLogin(InMemoryTokenStore(), clock)

        assertNull(login.authorizationCode("https://ha.example.com/cozyhome/callback?code=%zz"))
        assertNull(login.authorizationCode("https://ha.example.com/cozyhome/callback?code="))
    }

    @Test
    fun `code exchange posts the grant and stores the tokens`() {
        val tokenAnswer = """{"access_token":"access-1","expires_in":1800,"refresh_token":"refresh-1"}"""
        LocalHaServer { CannedResponse.json(200, tokenAnswer) }.use { server ->
            val store = InMemoryTokenStore()
            val login = OAuthLogin(store, clock)

            login.exchangeCode(server.baseUrl + "/", "the-code")

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/auth/token", request.rawPath)
            assertEquals("application/x-www-form-urlencoded", request.contentType)
            assertNull(request.authorization)
            val expectedForm = mapOf(
                "grant_type" to "authorization_code",
                "code" to "the-code",
                "client_id" to server.baseUrl + "/",
            )
            assertEquals(expectedForm, decodeForm(request.body))

            assertEquals(server.baseUrl, store.baseUrl)
            val expectedTokens = OAuthTokens(
                accessToken = "access-1",
                accessTokenExpiresAtMillis = 1_000_000 + 1_800_000,
                refreshToken = "refresh-1",
                clientId = server.baseUrl + "/",
            )
            assertEquals(expectedTokens, store.oAuthTokens)
        }
    }

    @Test
    fun `rejected code raises HaException and stores nothing`() {
        LocalHaServer { CannedResponse.json(400, """{"error":"invalid_request"}""") }.use { server ->
            val store = InMemoryTokenStore()
            val login = OAuthLogin(store, clock)

            val error = assertFailsWith<HaException> { login.exchangeCode(server.baseUrl, "expired-code") }

            assertEquals(400, error.httpStatus)
            assertNull(store.oAuthTokens)
            assertEquals("", store.baseUrl)
        }
    }

    @Test
    fun `token toString leaves the secrets out`() {
        val tokens = OAuthTokens("secret-access", 5, "secret-refresh", "https://ha.example.com/")

        assertFalse(tokens.toString().contains("secret"))
    }
}

/** Decodes an `application/x-www-form-urlencoded` body. */
internal fun decodeForm(body: String): Map<String, String> {
    return body.split("&").associate { field ->
        val name = URLDecoder.decode(field.substringBefore("="), StandardCharsets.UTF_8)
        val value = URLDecoder.decode(field.substringAfter("="), StandardCharsets.UTF_8)
        name to value
    }
}
