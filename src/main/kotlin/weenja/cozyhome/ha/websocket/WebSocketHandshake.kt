package weenja.cozyhome.ha.websocket

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The HTTP/1.1 upgrade that opens a WebSocket
 * ([RFC 6455 section 4](https://www.rfc-editor.org/rfc/rfc6455#section-4)).
 *
 * No subprotocols and no extensions (so no compression) are offered: Home Assistant needs
 * neither, and every extension would be more code to trust.
 */
internal object WebSocketHandshake {
    /** The fixed GUID from RFC 6455 section 1.3 that the server appends to the key. */
    private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val KEY_RANDOM_BYTES = 16
    private const val SWITCHING_PROTOCOLS = "101"
    private const val ACCEPT_HEADER = "sec-websocket-accept"
    private const val HEAD_TERMINATOR = "\r\n\r\n"

    /** Bounds the response head, so a server that never ends its headers cannot grow memory. */
    private const val MAX_HEAD_LENGTH = 16 * 1024

    /**
     * A fresh `Sec-WebSocket-Key`: 16 random bytes, Base64-encoded
     * ([RFC 6455 section 4.1](https://www.rfc-editor.org/rfc/rfc6455#section-4.1)).
     */
    fun newKey(random: SecureRandom): String {
        val nonce = ByteArray(KEY_RANDOM_BYTES)
        random.nextBytes(nonce)
        return Base64.getEncoder().encodeToString(nonce)
    }

    /** The upgrade request for [endpoint], ending with the blank line that closes the head. */
    fun request(endpoint: WebSocketEndpoint, key: String): String {
        return "GET ${endpoint.requestTarget} HTTP/1.1\r\n" +
            "Host: ${endpoint.hostHeader}\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
    }

    /**
     * The `Sec-WebSocket-Accept` value a real WebSocket server answers to [key]:
     * Base64 of the SHA-1 of the key followed by the RFC's GUID
     * ([RFC 6455 section 4.2.2](https://www.rfc-editor.org/rfc/rfc6455#section-4.2.2)).
     */
    fun expectedAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Reads the response head up to and including the blank line, byte by byte, so that no
     * frame bytes after it are consumed.
     */
    fun readResponseHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith(HEAD_TERMINATOR)) {
            val nextByte = input.read()
            if (nextByte < 0) {
                throw EOFException("WebSocket handshake closed")
            }
            head.append(nextByte.toChar())
            if (head.length > MAX_HEAD_LENGTH) {
                throw IOException("WebSocket handshake too long")
            }
        }
        return head.toString()
    }

    /**
     * Checks that [responseHead] accepts the upgrade for [key]
     * ([RFC 6455 section 4.1](https://www.rfc-editor.org/rfc/rfc6455#section-4.1), client
     * requirements). The accept check proves the server speaks WebSocket and is answering this
     * request, rather than a cache or a plain HTTP server echoing headers.
     *
     * @throws IOException when the status is not 101 or the accept value does not match.
     */
    fun verifyResponse(responseHead: String, key: String) {
        val lines = responseHead.split("\r\n")
        val statusLine = lines.first()
        val statusCode = statusLine.split(' ').getOrNull(1)
        if (statusCode != SWITCHING_PROTOCOLS) {
            throw IOException("WebSocket upgrade refused: $statusLine")
        }
        val accept = headerValue(lines, ACCEPT_HEADER)
        if (accept != expectedAccept(key)) {
            throw IOException("Bad Sec-WebSocket-Accept")
        }
    }

    /** Header names are case-insensitive (RFC 9110 section 5.1). */
    private fun headerValue(lines: List<String>, lowercaseName: String): String? {
        val headerLine = lines.firstOrNull { it.startsWith("$lowercaseName:", ignoreCase = true) }
        return headerLine?.substringAfter(':')?.trim()
    }
}
