package weenja.cozyhome.ha.websocket

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455) WebSocket client for Home Assistant's
 * `/api/websocket`, on the JDK alone.
 *
 * It does what that endpoint needs: text messages, masked client frames, fragmented messages, pings answered and
 * 64-bit lengths (a `get_states` answer can be several megabytes). No extensions, so no compression.
 *
 * All calls block. [send] may run on another thread than [receive], and [close] may be called from any thread.
 */
class WebSocketConnection internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
    private val random: SecureRandom = SecureRandom(),
    private val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
) : Closeable {
    /** Set once a close frame was sent, so it is sent at most once. */
    private val closeSent = AtomicBoolean(false)

    /**
     * Sends [text] as one final, masked text frame.
     *
     * @throws IOException when the connection fails.
     */
    fun send(text: String) {
        writeFrame(Opcode.TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Blocks until the next complete message arrives and returns it as UTF-8 text.
     *
     * Pings are answered with a pong carrying the same data, and a close frame from the server is answered to
     * finish the closing handshake ([RFC 6455 section 5.5](https://www.rfc-editor.org/rfc/rfc6455#section-5.5)).
     *
     * @return the message, or null once the connection is closed.
     * @throws IOException on a read timeout, a broken connection or a message above the size limit.
     */
    fun receive(): String? {
        val message = ByteArrayOutputStream()
        while (!closeSent.get()) {
            val frame = FrameCodec.read(input, maxMessageBytes) ?: return null
            // Pongs need no answer, and reserved opcodes are skipped rather than treated as fatal.
            when (frame.opcode) {
                Opcode.TEXT, Opcode.BINARY, Opcode.CONTINUATION -> {
                    appendFragment(message, frame.payload)
                    if (frame.isFinal) {
                        return message.toString(Charsets.UTF_8)
                    }
                }
                // Control frames carry at most 125 bytes; a longer ping is broken, but still gets a valid pong.
                Opcode.PING -> writeFrame(Opcode.PONG, frame.payload.copyOf(minOf(frame.payload.size, 125)))
                Opcode.CLOSE -> {
                    sendCloseOnce(ByteArray(0))
                    return null
                }
            }
        }
        return null
    }

    /**
     * Sends a close frame with status 1000 (normal closure), unless one was sent already, and closes the socket.
     * Errors are ignored, since the connection is being discarded either way.
     */
    override fun close() {
        sendCloseOnce(NORMAL_CLOSURE_PAYLOAD)
        try {
            transport.close()
        } catch (_: IOException) {
            // Nothing useful to do about a failed close.
        }
    }

    private fun appendFragment(message: ByteArrayOutputStream, fragment: ByteArray) {
        // A message may span many frames, each under the frame limit, so the total needs its own check.
        if (message.size().toLong() + fragment.size > maxMessageBytes) {
            throw IOException("WebSocket message too large")
        }
        message.write(fragment)
    }

    private fun sendCloseOnce(payload: ByteArray) {
        if (closeSent.compareAndSet(false, true)) {
            try {
                writeFrame(Opcode.CLOSE, payload)
            } catch (_: IOException) {
                // The peer may already have closed the TCP connection.
            }
        }
    }

    /** Synchronized, so frames written by [send] and by [receive] (pongs, close) never interleave. */
    @Synchronized
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val maskKey = ByteArray(FrameCodec.MASK_KEY_SIZE)
        random.nextBytes(maskKey)
        FrameCodec.write(output, Frame(isFinal = true, opcode = opcode, payload = payload), maskKey)
        output.flush()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 6_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 30_000

        /** Larger messages are refused; the largest real answers (`get_states`) are a few MB. */
        internal const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024
        private const val INPUT_BUFFER_BYTES = 64 * 1024

        /** Close status 1000 as two big-endian bytes (RFC 6455 section 7.4.1). */
        private val NORMAL_CLOSURE_PAYLOAD = byteArrayOf(0x03, 0xE8.toByte())

        /**
         * Opens `<baseUrl>/api/websocket` and completes the WebSocket handshake.
         *
         * `https` URLs use TLS with the platform's trusted certificates, and the certificate must match the host
         * name. Plain [SSLSocket]s skip that check unless endpoint identification is switched on, as it is here.
         *
         * @param baseUrl a Home Assistant base URL, for example `https://ha.example.com`.
         * @param readTimeoutMillis how long a read may block; 0 waits forever.
         * @throws IOException when the URL is invalid, the host is unreachable, the certificate
         *   does not match, or the server refuses the upgrade.
         */
        fun connect(
            baseUrl: String,
            connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        ): WebSocketConnection {
            val endpoint = WebSocketEndpoint.fromBaseUrl(baseUrl)
            val rawSocket = Socket()
            try {
                rawSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
                rawSocket.soTimeout = readTimeoutMillis
                // Commands are small request/response messages; Nagle's delay would only add latency.
                rawSocket.tcpNoDelay = true
                val socket = if (endpoint.isSecure) startTls(rawSocket, endpoint) else rawSocket
                return upgrade(socket, endpoint)
            } catch (exception: Exception) {
                try {
                    rawSocket.close()
                } catch (_: IOException) {
                    // The original exception is the one worth reporting.
                }
                throw exception
            }
        }

        private fun startTls(rawSocket: Socket, endpoint: WebSocketEndpoint): SSLSocket {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tlsSocket = factory.createSocket(rawSocket, endpoint.host, endpoint.port, true) as SSLSocket
            tlsSocket.sslParameters = tlsSocket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tlsSocket.startHandshake()
            return tlsSocket
        }

        private fun upgrade(socket: Socket, endpoint: WebSocketEndpoint): WebSocketConnection {
            val random = SecureRandom()
            val input = BufferedInputStream(socket.getInputStream(), INPUT_BUFFER_BYTES)
            val output = socket.getOutputStream()
            val key = WebSocketHandshake.newKey(random)
            output.write(WebSocketHandshake.request(endpoint, key).toByteArray(Charsets.US_ASCII))
            output.flush()
            val responseHead = WebSocketHandshake.readResponseHead(input)
            WebSocketHandshake.verifyResponse(responseHead, key)
            return WebSocketConnection(input, output, socket, random)
        }
    }
}
