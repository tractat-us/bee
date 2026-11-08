package us.tractat.bee.host

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.test.assertAll
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The last bytes an upgraded connection writes reach the dialler before the host hangs up (bee#58).
 *
 * Up to Ktor 3.6.0, the Netty engine closed an upgraded channel as soon as the upgrade's job
 * completed (`NettyHttp1ApplicationResponse.respondUpgrade`), while the coroutine that copies the
 * upgrade's output into Netty could still be a task queued on the channel's event loop. When the job
 * completed on that same loop, the close ran first and the bytes were dropped. On the WebSocket path
 * those bytes are the 1009 close frame of [FrameBoundTest], lost 13 times in 4,000 with two Netty
 * workers. Ktor 3.6.1 closes only after the copy has handed everything over (KTOR-9987).
 *
 * This forces that order every time, through the real engine path with a bare protocol upgrade in
 * place of the WebSocket plugin. One Netty thread runs both the channel and the call, so the upgrade
 * runs on the channel's loop, and [LastWord] writes its bytes, flushes and completes its job in a
 * single later task on that loop: the copy is queued behind the task that closes the channel.
 */
class UpgradedCloseTest {
    private val server = embeddedServer(Netty, configure = {
        connector {
            host = "127.0.0.1"
            port = 0
        }
        // One thread for the channels and the calls: shareWorkGroup sizes the group as their sum.
        shareWorkGroup = true
        workerGroupSize = 1
        callGroupSize = 0
    }) {
        intercept(ApplicationCallPipeline.Call) {
            call.respond(LastWord((call as NettyApplicationCall).context.executor()))
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    @AfterTest
    fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    @Test
    fun anUpgradeThatEndsOnItsChannelsLoopStillDeliversItsLastBytes() {
        val (status, received) = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write(
                    (
                        "GET /last-word HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                            "Upgrade: last-word\r\nConnection: Upgrade\r\n\r\n"
                        ).encodeToByteArray(),
                )
                flush()
            }
            val input = socket.getInputStream()
            input.readHeaders().lineSequence().first() to input.readBytes()
        }
        assertAll(
            { assertTrue(status.startsWith("HTTP/1.1 101"), "rig: the connection upgraded: $status") },
            { assertContentEquals(LAST_WORD, received, "bytes after the 101, up to the host's hang-up") },
        )
    }

    /**
     * An upgrade that writes [LAST_WORD] and ends, all in one task on [loop]: the channel's event
     * loop, which must also be the thread [upgrade] is called on, or the order isn't forced.
     */
    private class LastWord(private val loop: EventExecutor) : OutgoingContent.ProtocolUpgrade() {
        override val headers: Headers = Headers.build {
            append(HttpHeaders.Upgrade, "last-word")
            append(HttpHeaders.Connection, HttpHeaders.Upgrade)
        }

        override suspend fun upgrade(
            input: ByteReadChannel,
            output: ByteWriteChannel,
            engineContext: CoroutineContext,
            userContext: CoroutineContext,
        ): Job {
            // On the loop, the engine registers its close on this job before the launched task runs.
            check(loop.inEventLoop()) { "rig: the upgrade runs on the channel's event loop" }
            return CoroutineScope(loop.asCoroutineDispatcher()).launch {
                output.writeFully(LAST_WORD)
                output.flush()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        /** A WebSocket close frame, code 1009: what the host's last word is on the frame bound. */
        val LAST_WORD = byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xF1.toByte())

        /** Reads the HTTP response's head, up to the blank line. */
        fun InputStream.readHeaders(): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(read().also { check(it >= 0) { "the host hung up mid-handshake: $head" } }.toChar())
            return head.toString()
        }
    }
}
