package weenja.cozyhome.ha.websocket

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encodes and decodes single WebSocket frames on plain streams
 * ([RFC 6455 section 5.2](https://www.rfc-editor.org/rfc/rfc6455#section-5.2)).
 *
 * It knows nothing about sockets, messages or the closing handshake, so every length and
 * masking rule can be tested with byte arrays. Extensions are never negotiated, so the
 * RSV bits are neither set nor interpreted.
 */
internal object FrameCodec {
    private const val FIN_BIT = 0x80
    private const val OPCODE_MASK = 0x0F
    private const val MASK_BIT = 0x80
    private const val PAYLOAD_LENGTH_MASK = 0x7F
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** Largest length that fits in the 7-bit field of the second header byte. */
    private const val MAX_SEVEN_BIT_LENGTH = 125

    /** Second-byte length value meaning "the real length follows as 16 bits". */
    private const val SIXTEEN_BIT_LENGTH_MARKER = 126

    /** Second-byte length value meaning "the real length follows as 64 bits". */
    private const val SIXTY_FOUR_BIT_LENGTH_MARKER = 127

    private const val MAX_SIXTEEN_BIT_LENGTH = 0xFFFF
    private const val SIXTY_FOUR_BIT_LENGTH_BYTES = 8

    /** Size of the masking key ([RFC 6455 section 5.3](https://www.rfc-editor.org/rfc/rfc6455#section-5.3)). */
    const val MASK_KEY_SIZE = 4

    /**
     * Writes [frame] to [output] without flushing.
     *
     * @param maskKey four random bytes. A client must mask every frame it sends
     *   ([RFC 6455 section 5.1](https://www.rfc-editor.org/rfc/rfc6455#section-5.1)) so that
     *   proxies cannot be tricked into caching attacker-chosen bytes. Null writes an unmasked
     *   frame, which only a server may send.
     */
    fun write(output: OutputStream, frame: Frame, maskKey: ByteArray?) {
        require(maskKey == null || maskKey.size == MASK_KEY_SIZE) { "A masking key has $MASK_KEY_SIZE bytes" }
        val finBit = if (frame.isFinal) FIN_BIT else 0
        output.write(finBit or frame.opcode)
        writeLength(output, frame.payload.size, isMasked = maskKey != null)
        if (maskKey == null) {
            output.write(frame.payload)
        } else {
            output.write(maskKey)
            output.write(applyMask(frame.payload, maskKey))
        }
    }

    /**
     * Reads the next frame from [input], removing the mask when the sender applied one.
     *
     * @param maxPayloadBytes frames announcing a longer payload are rejected before any buffer is
     *   allocated, so a broken or hostile server cannot exhaust memory with one header.
     * @return the frame, or null when the stream ended cleanly before its first byte.
     * @throws EOFException when the stream ends inside a frame.
     * @throws IOException when the announced length is invalid or above [maxPayloadBytes].
     */
    fun read(input: InputStream, maxPayloadBytes: Int): Frame? {
        val firstByte = input.read()
        if (firstByte < 0) {
            return null
        }
        val secondByte = readByte(input)
        val payloadLength = readPayloadLength(input, secondByte and PAYLOAD_LENGTH_MASK)
        if (payloadLength > maxPayloadBytes) {
            throw IOException("WebSocket frame too large: $payloadLength bytes")
        }
        val isMasked = secondByte and MASK_BIT != 0
        val maskKey = if (isMasked) readFully(input, MASK_KEY_SIZE) else null
        val rawPayload = readFully(input, payloadLength.toInt())
        val payload = if (maskKey == null) rawPayload else applyMask(rawPayload, maskKey)
        return Frame(
            isFinal = firstByte and FIN_BIT != 0,
            opcode = firstByte and OPCODE_MASK,
            payload = payload,
        )
    }

    /**
     * XORs [data] with the repeating four-byte [maskKey]. Masking and unmasking are the same
     * operation ([RFC 6455 section 5.3](https://www.rfc-editor.org/rfc/rfc6455#section-5.3)).
     */
    fun applyMask(data: ByteArray, maskKey: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in data.indices) {
            val keyByte = maskKey[i % MASK_KEY_SIZE]
            result[i] = (data[i].toInt() xor keyByte.toInt()).toByte()
        }
        return result
    }

    /** Writes the second header byte and, for longer payloads, the extended length field. */
    private fun writeLength(output: OutputStream, length: Int, isMasked: Boolean) {
        val maskBit = if (isMasked) MASK_BIT else 0
        when {
            length <= MAX_SEVEN_BIT_LENGTH -> {
                output.write(maskBit or length)
            }
            length <= MAX_SIXTEEN_BIT_LENGTH -> {
                output.write(maskBit or SIXTEEN_BIT_LENGTH_MARKER)
                writeBigEndian(output, length.toLong(), byteCount = 2)
            }
            else -> {
                output.write(maskBit or SIXTY_FOUR_BIT_LENGTH_MARKER)
                writeBigEndian(output, length.toLong(), byteCount = SIXTY_FOUR_BIT_LENGTH_BYTES)
            }
        }
    }

    /**
     * Turns the 7-bit length field into the real payload length, reading the extended field
     * that follows it when needed. Lengths are big-endian ("network byte order").
     */
    private fun readPayloadLength(input: InputStream, sevenBitLength: Int): Long {
        return when (sevenBitLength) {
            SIXTEEN_BIT_LENGTH_MARKER -> readBigEndian(input, byteCount = 2)
            SIXTY_FOUR_BIT_LENGTH_MARKER -> {
                val length = readBigEndian(input, byteCount = SIXTY_FOUR_BIT_LENGTH_BYTES)
                // The most significant bit must be 0; as a signed Long it would read as negative.
                if (length < 0) {
                    throw IOException("Invalid WebSocket frame length")
                }
                length
            }
            else -> sevenBitLength.toLong()
        }
    }

    private fun writeBigEndian(output: OutputStream, value: Long, byteCount: Int) {
        for (byteIndex in byteCount - 1 downTo 0) {
            val shifted = value ushr (byteIndex * BITS_PER_BYTE)
            output.write((shifted and BYTE_MASK.toLong()).toInt())
        }
    }

    private fun readBigEndian(input: InputStream, byteCount: Int): Long {
        var value = 0L
        repeat(byteCount) {
            value = (value shl BITS_PER_BYTE) or readByte(input).toLong()
        }
        return value
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) {
            throw EOFException("WebSocket closed inside a frame")
        }
        return value
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) {
                throw EOFException("WebSocket closed inside a frame")
            }
            offset += count
        }
        return buffer
    }
}
