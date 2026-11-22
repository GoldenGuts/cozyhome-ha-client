package weenja.cozyhome.ha.websocket

import java.net.MalformedURLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketEndpointTest {
    @Test
    fun `http maps to plain ws on the given port`() {
        val endpoint = WebSocketEndpoint.fromBaseUrl("http://192.168.1.20:8123")

        assertFalse(endpoint.isSecure)
        assertEquals("192.168.1.20", endpoint.host)
        assertEquals(8123, endpoint.port)
        assertEquals("/api/websocket", endpoint.requestTarget)
        assertEquals("192.168.1.20:8123", endpoint.hostHeader)
    }

    @Test
    fun `https maps to wss on port 443 by default`() {
        val endpoint = WebSocketEndpoint.fromBaseUrl("https://ha.example.com")

        assertTrue(endpoint.isSecure)
        assertEquals(443, endpoint.port)
        assertEquals("ha.example.com", endpoint.hostHeader)
    }

    @Test
    fun `http defaults to port 80`() {
        assertEquals(80, WebSocketEndpoint.fromBaseUrl("http://homeassistant.local").port)
    }

    @Test
    fun `keeps a reverse proxy base path and ignores trailing slashes`() {
        val endpoint = WebSocketEndpoint.fromBaseUrl("https://example.com/home-assistant/")

        assertEquals("/home-assistant/api/websocket", endpoint.requestTarget)
    }

    @Test
    fun `scheme is case-insensitive`() {
        assertTrue(WebSocketEndpoint.fromBaseUrl("HTTPS://ha.example.com").isSecure)
    }

    @Test
    fun `rejects URLs that are not http or https`() {
        assertFailsWith<MalformedURLException> { WebSocketEndpoint.fromBaseUrl("ftp://ha.example.com") }
        assertFailsWith<MalformedURLException> { WebSocketEndpoint.fromBaseUrl("homeassistant.local:8123") }
        assertFailsWith<MalformedURLException> { WebSocketEndpoint.fromBaseUrl("http://bad host") }
    }
}
