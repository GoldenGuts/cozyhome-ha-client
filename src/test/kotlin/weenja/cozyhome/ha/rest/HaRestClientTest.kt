package weenja.cozyhome.ha.rest

import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.auth.AccessTokenProvider
import weenja.cozyhome.ha.auth.InMemoryTokenStore
import weenja.cozyhome.ha.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaRestClientTest {
    /** Hands out "token-1", and "token-2" once a refresh is forced; remembers each call. */
    private class RecordingTokenProvider : AccessTokenProvider {
        val forcedRefreshes: MutableList<String> = mutableListOf()

        override fun accessToken(baseUrl: String, forceRefresh: Boolean): String {
            if (forceRefresh) {
                forcedRefreshes.add(baseUrl)
            }
            return if (forcedRefreshes.isEmpty()) "token-1" else "token-2"
        }
    }

    private val tokenProvider = RecordingTokenProvider()

    private fun clientFor(baseUrl: String, fallbackUrl: String = "", longLivedToken: String? = null): HaRestClient {
        val store = InMemoryTokenStore(baseUrl, fallbackUrl, longLivedToken)
        val provider = if (longLivedToken == null) tokenProvider else AccessTokenProvider { _, _ -> longLivedToken }
        return HaRestClient(store, provider)
    }

    @Test
    fun `states are fetched with the bearer token and parsed`() {
        LocalHaServer { CannedResponse.json(200, """[{"entity_id":"light.kitchen","state":"on"}]""") }.use { server ->
            val states = clientFor(server.baseUrl).states()

            assertEquals("on", states.at(0)?.str("state"))
            val request = server.requests.single()
            assertEquals("/api/states", request.rawPath)
            assertEquals("Bearer token-1", request.authorization)
        }
    }

    @Test
    fun `a 401 is retried once with a refreshed token`() {
        val server = LocalHaServer { request ->
            if (request.authorization == "Bearer token-2") {
                CannedResponse.json(200, """{"entity_id":"sun.sun","state":"above_horizon"}""")
            } else {
                CannedResponse(401)
            }
        }
        server.use {
            val state = clientFor(server.baseUrl).state("sun.sun")

            assertEquals("above_horizon", state?.str("state"))
            assertEquals(listOf(server.baseUrl), tokenProvider.forcedRefreshes)
            assertEquals(listOf("Bearer token-1", "Bearer token-2"), server.requests.map { it.authorization })
        }
    }

    @Test
    fun `a 401 with a long-lived token is not retried`() {
        LocalHaServer { CannedResponse(401) }.use { server ->
            val client = clientFor(server.baseUrl, longLivedToken = "long-lived")

            val error = assertFailsWith<HaException> { client.states() }

            assertEquals(401, error.httpStatus)
            assertEquals(1, server.requests.size)
            assertEquals("Bearer long-lived", server.requests.single().authorization)
        }
    }

    @Test
    fun `fallback URL is used when the primary refuses connections`() {
        LocalHaServer { CannedResponse.json(200, "[]") }.use { fallback ->
            val client = clientFor(LocalHaServer.refusingUrl(), fallbackUrl = fallback.baseUrl)

            client.callService("light", "turn_on", Json.obj("entity_id" to "light.kitchen"))

            val request = fallback.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/api/services/light/turn_on", request.rawPath)
            assertEquals("application/json", request.contentType)
            assertEquals("""{"entity_id":"light.kitchen"}""", request.body)
        }
    }

    @Test
    fun `an error answer from the primary does not fall back`() {
        LocalHaServer { CannedResponse(500) }.use { primary ->
            LocalHaServer { CannedResponse.json(200, "[]") }.use { fallback ->
                val client = clientFor(primary.baseUrl, fallbackUrl = fallback.baseUrl)

                val error = assertFailsWith<HaException> { client.states() }

                assertEquals(500, error.httpStatus)
                assertTrue(fallback.requests.isEmpty())
            }
        }
    }

    @Test
    fun `network failure on every URL surfaces as IOException`() {
        val client = clientFor(LocalHaServer.refusingUrl(), fallbackUrl = LocalHaServer.refusingUrl())

        val error = assertFailsWith<IOException> { client.states() }

        assertFalse(error is HaException)
    }

    @Test
    fun `unknown entity gives null`() {
        LocalHaServer { CannedResponse.json(404, """{"message":"Entity not found."}""") }.use { server ->
            assertNull(clientFor(server.baseUrl).state("light.missing"))
        }
    }

    @Test
    fun `service with response asks for return_response and unwraps service_response`() {
        val answer = """{"changed_states":[],"service_response":{"todo.shopping":{"items":[]}}}"""
        LocalHaServer { CannedResponse.json(200, answer) }.use { server ->
            val client = clientFor(server.baseUrl)

            val response = client.callServiceWithResponse("todo", "get_items", Json.obj("entity_id" to "todo.shopping"))

            assertEquals(0, response?.get("todo.shopping")?.arr("items")?.size)
            val request = server.requests.single()
            assertEquals("/api/services/todo/get_items", request.rawPath)
            assertEquals("return_response", request.rawQuery)
        }
    }

    @Test
    fun `history parameters are URL-encoded`() {
        LocalHaServer { CannedResponse.json(200, "[[]]") }.use { server ->
            val client = clientFor(server.baseUrl)

            client.history(listOf("sensor.temp", "sensor.humidity"), "2026-11-22T10:00:00+01:00")

            val request = server.requests.single()
            assertEquals("/api/history/period/2026-11-22T10%3A00%3A00%2B01%3A00", request.rawPath)
            val expectedQuery = "filter_entity_id=sensor.temp%2Csensor.humidity&minimal_response&no_attributes"
            assertEquals(expectedQuery, request.rawQuery)
        }
    }

    @Test
    fun `history of no entities makes no request`() {
        LocalHaServer { CannedResponse.json(200, "[]") }.use { server ->
            val history = clientFor(server.baseUrl).history(emptyList(), "2026-11-22T10:00:00Z")

            assertTrue(history.list.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `calendar parameters are URL-encoded`() {
        LocalHaServer { CannedResponse.json(200, "[]") }.use { server ->
            val client = clientFor(server.baseUrl)

            client.calendarEvents("calendar.family", "2026-11-22T00:00:00+05:30", "2026-11-29T00:00:00+05:30")

            val request = server.requests.single()
            assertEquals("/api/calendars/calendar.family", request.rawPath)
            val expectedQuery = "start=2026-11-22T00%3A00%3A00%2B05%3A30&end=2026-11-29T00%3A00%3A00%2B05%3A30"
            assertEquals(expectedQuery, request.rawQuery)
        }
    }

    @Test
    fun `ping accepts 401 and rejects an unreachable URL`() {
        LocalHaServer { CannedResponse(401) }.use { server ->
            val client = clientFor(server.baseUrl)

            assertTrue(client.ping(server.baseUrl))
            assertNull(server.requests.single().authorization)
            assertFalse(client.ping(LocalHaServer.refusingUrl()))
            assertFalse(client.ping("not a url"))
        }
    }

    @Test
    fun `image from the user's own host is fetched with the token`() {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        LocalHaServer { CannedResponse(200, imageBytes) }.use { server ->
            val client = clientFor(server.baseUrl)

            val fromPath = client.fetchImage("/api/media_player_proxy/media_player.tv?token=abc")
            val fromAbsoluteUrl = client.fetchImage(server.baseUrl + "/local/avatar.png")

            assertContentEquals(imageBytes, fromPath)
            assertContentEquals(imageBytes, fromAbsoluteUrl)
            val expectedPaths = listOf("/api/media_player_proxy/media_player.tv", "/local/avatar.png")
            assertEquals(expectedPaths, server.requests.map { it.rawPath })
            assertTrue(server.requests.all { it.authorization == "Bearer token-1" })
        }
    }

    @Test
    fun `image from a foreign host is refused without any request`() {
        LocalHaServer { CannedResponse(200, byteArrayOf(1)) }.use { server ->
            val client = clientFor(server.baseUrl)
            val foreignUrls = listOf(
                "https://artwork.example.com/cover.jpg",
                // Each of these starts with the configured URL, so a string-prefix check would let it through.
                server.baseUrl + "@artwork.example.com/cover.jpg",
                server.baseUrl.replace("127.0.0.1", "127.0.0.1.nip.io") + "/cover.jpg",
                "@artwork.example.com/cover.jpg",
                "//artwork.example.com/cover.jpg",
            )

            for (url in foreignUrls) {
                assertNull(client.fetchImage(url), url)
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `image redirect is not followed`() {
        val server = LocalHaServer { request ->
            if (request.rawPath == "/redirected.png") {
                CannedResponse(200, byteArrayOf(1))
            } else {
                CannedResponse(302, headers = mapOf("Location" to "/redirected.png"))
            }
        }
        server.use {
            val client = clientFor(server.baseUrl)

            val error = assertFailsWith<HaException> { client.fetchImage("/api/camera_proxy/camera.door") }

            assertEquals(302, error.httpStatus)
            assertEquals(listOf("/api/camera_proxy/camera.door"), server.requests.map { it.rawPath })
        }
    }
}
