package weenja.cozyhome.ha.websocket

import weenja.cozyhome.ha.json.Json
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A scripted Home Assistant WebSocket server on a loopback port.
 *
 * Each accepted connection gets the HTTP upgrade and then runs [conversation] on a
 * [ClientChannel]. Failures inside the conversation are collected in [failures] so a test
 * can assert the server side saw what it expected.
 */
internal class FakeHomeAssistant(
    private val conversation: (ClientChannel) -> Unit,
) : Closeable {
    private val serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    private val acceptedCount = AtomicInteger()

    /** Errors thrown by the conversation, in the order they happened. */
    val failures: MutableList<Throwable> = CopyOnWriteArrayList()

    /** Request targets of the upgrade requests, for example `/api/websocket`. */
    val requestTargets: MutableList<String> = CopyOnWriteArrayList()

    /** The base URL a client should use. */
    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    /** How many TCP connections the server accepted. */
    val acceptedConnections: Int
        get() = acceptedCount.get()

    init {
        thread(isDaemon = true, name = "fake-home-assistant") {
            acceptLoop()
        }
    }

    override fun close() {
        serverSocket.close()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: SocketException) {
                return
            }
            acceptedCount.incrementAndGet()
            socket.use { serve(it) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            acceptUpgrade(input, output)
            conversation(ClientChannel(input, output))
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }

    private fun acceptUpgrade(input: InputStream, output: OutputStream) {
        // A request head ends with the same blank line as a response head.
        val requestHead = WebSocketHandshake.readResponseHead(input)
        val lines = requestHead.split("\r\n")
        requestTargets.add(lines.first().split(' ')[1])
        val keyLine = lines.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        val key = keyLine.substringAfter(':').trim()
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${WebSocketHandshake.expectedAccept(key)}\r\n" +
            "\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** The server's end of one WebSocket connection. */
    internal class ClientChannel(
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        /** Sends [json] as one unmasked text frame, as a server does. */
        fun send(json: String) {
            val frame = Frame(isFinal = true, opcode = Opcode.TEXT, payload = json.toByteArray())
            FrameCodec.write(output, frame, maskKey = null)
            output.flush()
        }

        /** Reads the next text message from the client and parses it. */
        fun receive(): Json {
            val frame = FrameCodec.read(input, MAX_PAYLOAD_BYTES) ?: throw IOException("Client closed the connection")
            check(frame.opcode == Opcode.TEXT) { "Expected a text frame, got opcode ${frame.opcode}" }
            return Json.parse(frame.payload.decodeToString())
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
    }
}
