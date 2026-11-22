package weenja.cozyhome.ha.websocket

/**
 * One WebSocket frame after the mask, if any, has been removed
 * ([RFC 6455 section 5.2](https://www.rfc-editor.org/rfc/rfc6455#section-5.2)).
 *
 * @property isFinal the FIN bit: false while more fragments of the same message follow.
 * @property opcode one of the [Opcode] constants.
 * @property payload the unmasked application data.
 */
internal class Frame(
    val isFinal: Boolean,
    val opcode: Int,
    val payload: ByteArray,
)

/**
 * Frame opcodes from [RFC 6455 section 5.2](https://www.rfc-editor.org/rfc/rfc6455#section-5.2).
 */
internal object Opcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}
