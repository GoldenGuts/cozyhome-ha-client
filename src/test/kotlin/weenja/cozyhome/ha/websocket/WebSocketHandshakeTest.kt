package weenja.cozyhome.ha.websocket

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebSocketHandshakeTest {
    @Test
    fun `accept value matches the example in RFC 6455 section 1_3`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketHandshake.expectedAccept(RFC_EXAMPLE_KEY))
    }

    @Test
    fun `new keys are 16 random bytes in Base64`() {
        val random = SecureRandom()

        val first = WebSocketHandshake.newKey(random)
        val second = WebSocketHandshake.newKey(random)

        assertEquals(16, Base64.getDecoder().decode(first).size)
        assertNotEquals(first, second)
    }

    @Test
    fun `request asks for an upgrade on the API path`() {
        val endpoint = WebSocketEndpoint.fromBaseUrl("http://homeassistant.local:8123/ha")

        val request = WebSocketHandshake.request(endpoint, RFC_EXAMPLE_KEY)

        assertTrue(request.startsWith("GET /ha/api/websocket HTTP/1.1\r\n"))
        assertTrue(request.contains("\r\nHost: homeassistant.local:8123\r\n"))
        assertTrue(request.contains("\r\nUpgrade: websocket\r\n"))
        assertTrue(request.contains("\r\nSec-WebSocket-Key: $RFC_EXAMPLE_KEY\r\n"))
        assertTrue(request.contains("\r\nSec-WebSocket-Version: 13\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `accepts a 101 response with the right accept value in any header case`() {
        val head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "upgrade: websocket\r\n" +
            "SEC-WEBSOCKET-ACCEPT: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
            "\r\n"

        WebSocketHandshake.verifyResponse(head, RFC_EXAMPLE_KEY)
    }

    @Test
    fun `rejects a wrong accept value`() {
        val head = "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: bm90IHRoZSByaWdodCBvbmU=\r\n\r\n"

        val error = assertFailsWith<IOException> { WebSocketHandshake.verifyResponse(head, RFC_EXAMPLE_KEY) }

        assertEquals("Bad Sec-WebSocket-Accept", error.message)
    }

    @Test
    fun `rejects a missing accept value`() {
        val head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"

        assertFailsWith<IOException> { WebSocketHandshake.verifyResponse(head, RFC_EXAMPLE_KEY) }
    }

    @Test
    fun `rejects a response that is not 101`() {
        val head = "HTTP/1.1 404 Not Found\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"

        val error = assertFailsWith<IOException> { WebSocketHandshake.verifyResponse(head, RFC_EXAMPLE_KEY) }

        assertTrue(error.message.orEmpty().contains("404"))
    }

    @Test
    fun `reads the head without consuming the first frame`() {
        val head = "HTTP/1.1 101 Switching Protocols\r\n\r\n"
        val input = ByteArrayInputStream(head.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x81.toByte()))

        assertEquals(head, WebSocketHandshake.readResponseHead(input))
        assertEquals(0x81, input.read())
    }

    @Test
    fun `fails when the server closes during the head`() {
        val input = ByteArrayInputStream("HTTP/1.1 101 Switching".toByteArray(Charsets.US_ASCII))

        assertFailsWith<EOFException> { WebSocketHandshake.readResponseHead(input) }
    }

    @Test
    fun `refuses a head that never ends`() {
        val input = ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() })

        assertFailsWith<IOException> { WebSocketHandshake.readResponseHead(input) }
    }

    private companion object {
        /** The sample nonce from RFC 6455 section 1.3. */
        const val RFC_EXAMPLE_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
    }
}
