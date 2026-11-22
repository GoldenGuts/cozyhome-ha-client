package weenja.cozyhome.ha.websocket

import org.junit.jupiter.api.Timeout
import weenja.cozyhome.ha.HaException
import weenja.cozyhome.ha.auth.AccessTokenProvider
import weenja.cozyhome.ha.rest.LocalHaServer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end tests against [FakeHomeAssistant] on loopback sockets. */
@Timeout(10)
class HaSessionTest {
    private val servers = ArrayList<FakeHomeAssistant>()
    private val tokenRequests = CopyOnWriteArrayList<String>()
    private val tokenProvider = AccessTokenProvider { baseUrl, _ ->
        tokenRequests.add(baseUrl)
        ACCESS_TOKEN
    }

    @AfterTest
    fun stopServers() {
        servers.forEach { it.close() }
    }

    @Test
    fun `authenticates, runs a command and queues events that arrive before the result`() {
        val server = startServer { channel ->
            authenticate(channel)
            val command = channel.receive()
            check(command.str("type") == "get_config") { "Unexpected command $command" }
            val commandId = command.str("id")
            channel.send(
                "[{\"id\":99,\"type\":\"event\",\"event\":{\"event_type\":\"lovelace_updated\"}}," +
                    "{\"id\":$commandId,\"type\":\"result\",\"success\":true,\"result\":{\"location_name\":\"Home\"}}]",
            )
        }

        val (locationName, eventType) = HaSession.open(listOf(server.baseUrl + "/"), tokenProvider) { session ->
            assertEquals(HA_VERSION, session.haVersion)
            val config = session.call(HaCommand("get_config"))
            val event = session.nextEvent()
            config?.str("location_name") to event?.str("event_type")
        }

        assertEquals("Home", locationName)
        assertEquals("lovelace_updated", eventType)
        assertEquals(listOf(server.baseUrl), tokenRequests)
        assertEquals(listOf("/api/websocket"), server.requestTargets)
        assertTrue(server.failures.isEmpty(), "server saw: ${server.failures}")
    }

    @Test
    fun `an error result throws from call and gives null from tryCall`() {
        val server = startServer { channel ->
            authenticate(channel)
            repeat(2) {
                val commandId = channel.receive().str("id")
                channel.send(
                    "{\"id\":$commandId,\"type\":\"result\",\"success\":false," +
                        "\"error\":{\"code\":\"unknown_command\",\"message\":\"Unknown command.\"}}",
                )
            }
        }

        HaSession.open(listOf(server.baseUrl), tokenProvider) { session ->
            val error = assertFailsWith<HaException> { session.call(HaCommand("floor_registry/list")) }
            assertEquals("floor_registry/list: unknown_command Unknown command.", error.message)
            assertNull(session.tryCall(HaCommand("floor_registry/list")))
        }
    }

    @Test
    fun `a rejected token throws HaException and does not try the fallback URL`() {
        val rejecting = startServer { channel ->
            channel.send("{\"type\":\"auth_required\",\"ha_version\":\"$HA_VERSION\"}")
            channel.receive()
            channel.send("{\"type\":\"auth_invalid\",\"message\":\"Invalid access token or password\"}")
        }
        val fallback = startServer { channel -> authenticate(channel) }

        val error = assertFailsWith<HaException> {
            HaSession.open(listOf(rejecting.baseUrl, fallback.baseUrl), tokenProvider) { }
        }

        assertEquals(401, error.httpStatus)
        assertTrue(error.message.orEmpty().contains("Invalid access token"))
        assertEquals(0, fallback.acceptedConnections)
    }

    @Test
    fun `falls back to the next URL when the first refuses connections`() {
        val server = startServer { channel -> authenticate(channel) }
        val refusingUrl = LocalHaServer.refusingUrl()

        val version = HaSession.open(listOf(refusingUrl, server.baseUrl), tokenProvider) { it.haVersion }

        assertEquals(HA_VERSION, version)
        assertEquals(listOf(server.baseUrl), tokenRequests)
    }

    @Test
    fun `throws the last network error when no URL is reachable`() {
        assertFailsWith<IOException> {
            HaSession.open(listOf(LocalHaServer.refusingUrl(), LocalHaServer.refusingUrl()), tokenProvider) { }
        }
    }

    @Test
    fun `throws when no URL is configured`() {
        val error = assertFailsWith<IOException> { HaSession.open(emptyList(), tokenProvider) { } }

        assertEquals("No Home Assistant URL is configured", error.message)
    }

    @Test
    fun `a server that closes during auth is a network error`() {
        val server = startServer { channel ->
            channel.send("{\"type\":\"auth_required\"}")
            channel.receive()
        }

        val error = assertFailsWith<IOException> {
            HaSession.connect(server.baseUrl, tokenProvider, readTimeoutMillis = 2_000)
        }

        assertTrue(error !is HaException)
    }

    private fun startServer(conversation: (FakeHomeAssistant.ClientChannel) -> Unit): FakeHomeAssistant {
        val server = FakeHomeAssistant(conversation)
        servers.add(server)
        return server
    }

    /** The authentication phase as Home Assistant runs it, checking the token the client sent. */
    private fun authenticate(channel: FakeHomeAssistant.ClientChannel) {
        channel.send("{\"type\":\"auth_required\",\"ha_version\":\"$HA_VERSION\"}")
        val auth = channel.receive()
        check(auth.str("type") == "auth") { "Expected auth, got $auth" }
        check(auth.str("access_token") == ACCESS_TOKEN) { "Wrong access token" }
        channel.send("{\"type\":\"auth_ok\",\"ha_version\":\"$HA_VERSION\"}")
    }

    private companion object {
        const val ACCESS_TOKEN = "test-access-token"
        const val HA_VERSION = "2026.11.1"
    }
}
