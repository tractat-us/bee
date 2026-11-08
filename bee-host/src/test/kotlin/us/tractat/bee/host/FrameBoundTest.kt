package us.tractat.bee.host

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.test.assertAll
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The host's WebSocket frame bound ([MAX_FRAME_BYTES]), on a real Netty server with the routes
 * [mountEndpoints] mounts, dialled over a raw socket from loopback so the key gate lets it through.
 *
 * The dialler sends only a frame **header** announcing one byte over the bound, and no body. Ktor
 * checks a header's length against `maxFrameSize` before it allocates the body's buffer
 * (`WebSocketReader.parseLoop`), so a close with code 1009 (too big) in answer to a header alone is
 * the refusal before allocation. Unbounded, the host allocates the announced size and waits for the
 * body, and no close comes: the read times out.
 */
class FrameBoundTest {
    private val clock = { Clock.System.now() }
    private val pairing = Pairing(clock, SecureSecrets(SecureRandom()))
    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        mountEndpoints(pairing, PeerAddress.DIRECT, PairingDesk(pairing, CompletableDeferred(), Pairing.DWELL, clock))
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    @AfterTest
    fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    @Test
    fun aFrameHeaderOverTheBoundClosesTheBoardSocketAsTooBig() {
        val close = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(upgrade(port).encodeToByteArray())
            out.flush()
            val status = input.readHeaders().lineSequence().first()
            out.write(binaryFrameHeader(MAX_FRAME_BYTES + 1))
            out.flush()
            status to input.awaitClose()
        }
        assertAll(
            { assertTrue(close.first.startsWith("HTTP/1.1 101"), "rig: the board socket upgraded: ${close.first}") },
            { assertEquals(TOO_BIG, close.second.code, "close reason: ${close.second.reason}") },
        )
    }

    private class Close(val code: Int, val reason: String)

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val TOO_BIG = 1009
        const val OP_CLOSE = 0x8
        const val PAYLOAD_LEN_16 = 126
        const val PAYLOAD_LEN_64 = 127

        fun upgrade(port: Int): String {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            return "GET ${BoardRelay.PATH}?peer=probe HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        }

        /** A masked, final binary frame's header announcing [length] bytes: what a client sends before the body. */
        fun binaryFrameHeader(length: Long): ByteArray =
            ByteBuffer.allocate(14).put(0x82.toByte()).put((0x80 or PAYLOAD_LEN_64).toByte()).putLong(length).putInt(0x01020304).array()

        /** Reads the HTTP response's head, up to the blank line. */
        fun InputStream.readHeaders(): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(read().also { check(it >= 0) { "the host hung up mid-handshake: $head" } }.toChar())
            return head.toString()
        }

        /** Reads the host's frames, skipping pings and the like, until its close frame. */
        fun DataInputStream.awaitClose(): Close {
            while (true) {
                val opcode = readUnsignedByte() and 0x0F
                val length = when (val short = readUnsignedByte() and 0x7F) {
                    PAYLOAD_LEN_16 -> readUnsignedShort().toLong()
                    PAYLOAD_LEN_64 -> readLong()
                    else -> short.toLong()
                }
                val payload = ByteArray(length.toInt()).also(::readFully)
                if (opcode == OP_CLOSE) {
                    val buffer = ByteBuffer.wrap(payload)
                    return Close(buffer.getShort().toInt() and 0xFFFF, payload.decodeToString(2, payload.size))
                }
            }
        }
    }
}
