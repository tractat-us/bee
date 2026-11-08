package us.tractat.bee.mcp

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A half-open link to the host, on real loopback sockets (bee#77): the host rebooted or the Wi-Fi
 * dropped, and no FIN or RST ever reaches the bridge. A socket that only reads never notices, and
 * the bridge would wait out the TCP retransmits, past the host's boot grace. [peerHttpClient]'s
 * ping tears the socket down instead.
 *
 * The server here never pings, so only the client's ping can find the dead link. Between them sits
 * a [SeverableProxy], whose [SeverableProxy.sever] stops forwarding without closing either socket:
 * a faithful half-open link, as a `close()` would send a FIN. The pattern is kuilt's
 * `WebSocketPingHalfOpenTest`.
 *
 * The signal is the close reason, not only that the socket closed: Ktor's pinger closes with
 * "Ping timeout", which nothing else in the rig produces. Without the ping the socket never closes
 * and the wait times out.
 */
class PeerHttpTest {
    private val interval = 500.milliseconds
    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        install(WebSockets)
        routing { webSocket(PATH) { for (frame in incoming) Unit } }
    }.start(wait = false)
    private val proxy = SeverableProxy(runBlocking { server.engine.resolvedConnectors().first().port })
    private val http = peerHttpClient(interval)

    @AfterTest
    fun stop() {
        http.close()
        proxy.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun aHalfOpenLinkClosesWithAPingTimeout(): Unit = runBlocking {
        val session = http.webSocketSession("ws://127.0.0.1:${proxy.port}$PATH")

        // Rig: across three pong timeouts on a live link, every ping is answered.
        delay(interval * 6)
        assertTrue(session.isActive && !session.closeReason.isCompleted, "rig: a live link stays open")

        proxy.sever()
        val reason = withTimeout(10.seconds) { session.closeReason.await() }
        assertEquals(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Ping timeout"), reason)

        // The client's session coroutines hang off this scope, and a half-open link never ends them.
        coroutineContext.cancelChildren()
    }

    private companion object {
        const val PATH = "/ws/half-open"
    }
}

/**
 * Forwards loopback TCP to [targetPort] until [sever], after which it reads and drops every byte
 * either way, closing nothing: both connections stay established, and neither end hears a FIN.
 * Blocking sockets on daemon threads; [close] ends them.
 */
private class SeverableProxy(private val targetPort: Int) : AutoCloseable {
    private val listener = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var severed = false

    val port: Int get() = listener.localPort

    init {
        thread(isDaemon = true, name = "severable-proxy-accept") {
            // A closed listener ends this loop with a SocketException, which is how close() stops it.
            runCatching {
                while (!listener.isClosed) {
                    val downstream = listener.accept()
                    val upstream = Socket("127.0.0.1", targetPort)
                    sockets += downstream
                    sockets += upstream
                    pump(downstream, upstream)
                    pump(upstream, downstream)
                }
            }
        }
    }

    fun sever() {
        severed = true
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true, name = "severable-proxy-pump") {
        // A closed socket ends this loop with an IOException, which is how close() stops it.
        runCatching {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (severed) continue
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    override fun close() {
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val BUFFER_BYTES = 8192
    }
}
