package weenja.cozyhome.ha.websocket

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameCodecTest {
    private val maskKey = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)

    @Test
    fun `masked text frame matches the example in RFC 6455 section 5_7`() {
        val encoded = encode(Frame(isFinal = true, opcode = Opcode.TEXT, payload = "Hello".toByteArray()), maskKey)

        val expected = bytes(0x81, 0x85, 0x37, 0xFA, 0x21, 0x3D, 0x7F, 0x9F, 0x4D, 0x51, 0x58)
        assertContentEquals(expected, encoded)
    }

    @Test
    fun `unmasked text frame matches the example in RFC 6455 section 5_7`() {
        val encoded = encode(Frame(isFinal = true, opcode = Opcode.TEXT, payload = "Hello".toByteArray()), null)

        assertContentEquals(bytes(0x81, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F), encoded)
    }

    @Test
    fun `decodes the masked RFC example back to its text`() {
        val wireBytes = bytes(0x81, 0x85, 0x37, 0xFA, 0x21, 0x3D, 0x7F, 0x9F, 0x4D, 0x51, 0x58)

        val frame = assertNotNull(decode(wireBytes))

        assertTrue(frame.isFinal)
        assertEquals(Opcode.TEXT, frame.opcode)
        assertEquals("Hello", frame.payload.decodeToString())
    }

    @Test
    fun `125 byte payload uses the 7-bit length`() {
        val encoded = encode(textFrame(125), maskKey)

        assertEquals(0x80 or 125, encoded[1].toInt() and 0xFF)
        assertEquals(2 + 4 + 125, encoded.size)
    }

    @Test
    fun `126 byte payload uses the 16-bit length`() {
        val encoded = encode(textFrame(126), maskKey)

        assertEquals(0x80 or 126, encoded[1].toInt() and 0xFF)
        assertContentEquals(bytes(0x00, 0x7E), encoded.copyOfRange(2, 4))
        assertEquals(2 + 2 + 4 + 126, encoded.size)
    }

    @Test
    fun `65535 byte payload still uses the 16-bit length`() {
        val encoded = encode(textFrame(65_535), maskKey)

        assertEquals(0x80 or 126, encoded[1].toInt() and 0xFF)
        assertContentEquals(bytes(0xFF, 0xFF), encoded.copyOfRange(2, 4))
    }

    @Test
    fun `65536 byte payload uses the 64-bit length`() {
        val encoded = encode(textFrame(65_536), maskKey)

        assertEquals(0x80 or 127, encoded[1].toInt() and 0xFF)
        assertContentEquals(bytes(0, 0, 0, 0, 0, 0x01, 0, 0), encoded.copyOfRange(2, 10))
        assertEquals(2 + 8 + 4 + 65_536, encoded.size)
    }

    @Test
    fun `round trips payloads at every length boundary, masked and unmasked`() {
        val lengths = listOf(0, 1, 125, 126, 127, 65_535, 65_536, 200_000)
        for (length in lengths) {
            for (key in listOf(maskKey, null)) {
                val original = textFrame(length)

                val decoded = assertNotNull(decode(encode(original, key)))

                assertContentEquals(original.payload, decoded.payload, "length $length, masked ${key != null}")
            }
        }
    }

    @Test
    fun `masking hides the payload on the wire`() {
        val payload = "{\"type\":\"auth\"}".toByteArray()

        val encoded = encode(Frame(isFinal = true, opcode = Opcode.TEXT, payload = payload), maskKey)

        val wirePayload = encoded.copyOfRange(2 + 4, encoded.size)
        assertFalse(wirePayload.contentEquals(payload))
        assertContentEquals(payload, FrameCodec.applyMask(wirePayload, maskKey))
    }

    @Test
    fun `keeps the FIN bit and opcode of fragments`() {
        val output = ByteArrayOutputStream()
        val firstFragment = Frame(isFinal = false, opcode = Opcode.TEXT, payload = "Hel".toByteArray())
        val lastFragment = Frame(isFinal = true, opcode = Opcode.CONTINUATION, payload = "lo".toByteArray())
        FrameCodec.write(output, firstFragment, maskKey)
        FrameCodec.write(output, lastFragment, maskKey)
        val input = ByteArrayInputStream(output.toByteArray())

        val first = assertNotNull(FrameCodec.read(input, MAX_PAYLOAD))
        val second = assertNotNull(FrameCodec.read(input, MAX_PAYLOAD))

        assertFalse(first.isFinal)
        assertEquals(Opcode.TEXT, first.opcode)
        assertTrue(second.isFinal)
        assertEquals(Opcode.CONTINUATION, second.opcode)
        assertEquals("Hello", (first.payload + second.payload).decodeToString())
    }

    @Test
    fun `returns null at a clean end of stream`() {
        assertNull(decode(ByteArray(0)))
    }

    @Test
    fun `fails when the stream ends inside a frame`() {
        val truncated = bytes(0x81, 0x05, 0x48, 0x65)

        assertFailsWith<EOFException> { decode(truncated) }
    }

    @Test
    fun `rejects a frame above the limit before reading its payload`() {
        val header = bytes(0x81, 0x7F, 0, 0, 0, 0, 0x04, 0, 0, 0x01)

        val error = assertFailsWith<IOException> { FrameCodec.read(ByteArrayInputStream(header), 64 * 1024 * 1024) }

        assertTrue(error.message.orEmpty().contains("too large"))
    }

    @Test
    fun `rejects a 64-bit length with the most significant bit set`() {
        val header = bytes(0x81, 0x7F, 0x80, 0, 0, 0, 0, 0, 0, 0x01)

        assertFailsWith<IOException> { decode(header) }
    }

    @Test
    fun `refuses a masking key that is not four bytes`() {
        assertFailsWith<IllegalArgumentException> { encode(textFrame(1), ByteArray(3)) }
    }

    private fun textFrame(length: Int): Frame {
        val payload = ByteArray(length) { index -> ('a' + index % 26).code.toByte() }
        return Frame(isFinal = true, opcode = Opcode.TEXT, payload = payload)
    }

    private fun encode(frame: Frame, key: ByteArray?): ByteArray {
        val output = ByteArrayOutputStream()
        FrameCodec.write(output, frame, key)
        return output.toByteArray()
    }

    private fun decode(wireBytes: ByteArray): Frame? {
        return FrameCodec.read(ByteArrayInputStream(wireBytes), MAX_PAYLOAD)
    }

    private fun bytes(vararg values: Int): ByteArray {
        return ByteArray(values.size) { index -> values[index].toByte() }
    }

    private companion object {
        const val MAX_PAYLOAD = 1024 * 1024
    }
}
