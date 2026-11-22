package weenja.cozyhome.ha.websocket

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Message-level behaviour on in-memory streams; [HaSessionTest] covers real sockets. */
class WebSocketConnectionTest {
    private val clientOutput = ByteArrayOutputStream()
    private val transport = RecordingTransport()

    @Test
    fun `sends text as one final masked frame`() {
        val connection = connectionReading(ByteArray(0))

        connection.send("{\"type\":\"auth\"}")

        val wireBytes = clientOutput.toByteArray()
        assertEquals(0x80, wireBytes[1].toInt() and 0x80, "mask bit")
        val frame = clientFrames().single()
        assertTrue(frame.isFinal)
        assertEquals(Opcode.TEXT, frame.opcode)
        assertEquals("{\"type\":\"auth\"}", frame.payload.decodeToString())
    }

    @Test
    fun `receives a text message`() {
        val connection = connectionReading(serverFrames(frame(Opcode.TEXT, "hello")))

        assertEquals("hello", connection.receive())
    }

    @Test
    fun `decodes text as UTF-8`() {
        val connection = connectionReading(serverFrames(frame(Opcode.TEXT, "Küche 21 °C")))

        assertEquals("Küche 21 °C", connection.receive())
    }

    @Test
    fun `joins fragments and answers a ping that arrives between them`() {
        val incoming = serverFrames(
            frame(Opcode.TEXT, "Hel", isFinal = false),
            frame(Opcode.PING, "are you there"),
            frame(Opcode.CONTINUATION, "lo"),
        )
        val connection = connectionReading(incoming)

        assertEquals("Hello", connection.receive())

        val pong = clientFrames().single()
        assertEquals(Opcode.PONG, pong.opcode)
        assertEquals("are you there", pong.payload.decodeToString())
    }

    @Test
    fun `ignores unsolicited pongs`() {
        val incoming = serverFrames(
            frame(Opcode.PONG, ""),
            frame(Opcode.TEXT, "after"),
        )
        val connection = connectionReading(incoming)

        assertEquals("after", connection.receive())
        assertTrue(clientFrames().isEmpty())
    }

    @Test
    fun `answers a close frame and then reports the end`() {
        val incoming = serverFrames(
            frame(Opcode.CLOSE, ""),
            frame(Opcode.TEXT, "never read"),
        )
        val connection = connectionReading(incoming)

        assertNull(connection.receive())
        assertNull(connection.receive())

        assertEquals(Opcode.CLOSE, clientFrames().single().opcode)
    }

    @Test
    fun `reports the end when the stream ends between messages`() {
        val connection = connectionReading(ByteArray(0))

        assertNull(connection.receive())
    }

    @Test
    fun `close sends status 1000 once and closes the transport`() {
        val connection = connectionReading(ByteArray(0))

        connection.close()
        connection.close()

        val closeFrame = clientFrames().single()
        assertEquals(Opcode.CLOSE, closeFrame.opcode)
        assertContentEquals(byteArrayOf(0x03, 0xE8.toByte()), closeFrame.payload)
        assertEquals(2, transport.closeCount)
    }

    @Test
    fun `refuses a message whose fragments together exceed the limit`() {
        val incoming = serverFrames(
            frame(Opcode.TEXT, "x".repeat(600), isFinal = false),
            frame(Opcode.CONTINUATION, "x".repeat(600)),
        )
        val connection = WebSocketConnection(
            ByteArrayInputStream(incoming),
            clientOutput,
            transport,
            maxMessageBytes = 1000,
        )

        assertFailsWith<IOException> { connection.receive() }
    }

    private fun connectionReading(serverBytes: ByteArray): WebSocketConnection {
        return WebSocketConnection(ByteArrayInputStream(serverBytes), clientOutput, transport)
    }

    private fun frame(opcode: Int, text: String, isFinal: Boolean = true): Frame {
        return Frame(isFinal = isFinal, opcode = opcode, payload = text.toByteArray())
    }

    /** Servers send unmasked frames. */
    private fun serverFrames(vararg frames: Frame): ByteArray {
        val output = ByteArrayOutputStream()
        for (frame in frames) {
            FrameCodec.write(output, frame, maskKey = null)
        }
        return output.toByteArray()
    }

    private fun clientFrames(): List<Frame> {
        val input = ByteArrayInputStream(clientOutput.toByteArray())
        return generateSequence { FrameCodec.read(input, WebSocketConnection.MAX_MESSAGE_BYTES) }.toList()
    }

    private class RecordingTransport : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
