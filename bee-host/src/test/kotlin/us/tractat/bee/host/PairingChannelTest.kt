package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingWire
import us.tractat.kuilt.test.assertAll
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The pairing channel on a real Netty server, from loopback: it is a stream of server-sent events,
 * never a WebSocket, so a keyless device has no inbound frame parser to feed (the host reads
 * nothing from it). Whether a hang-up reaches the host is Netty's to tell, so only a real server
 * proves it; the dwell here is longer than the test, so only the hang-up can end the pairing.
 */
class PairingChannelTest {
    private val clock = { Clock.System.now() }
    private val pairing = Pairing(clock, SecureSecrets(SecureRandom()))
    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        mountEndpoints(BoardId.mint(), pairing, SiteRule(emptySet()), PeerAddress.DIRECT, ArrivalAddress.DIRECT, PairingDesk(pairing, CompletableDeferred(), 10.minutes, clock))
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }
    private val client = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun stop() {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun aDeviceGetsItsCodeAsAnEventAndItsHangUpEndsThePairing() = runBlocking {
        val code = CompletableDeferred<PairingMessage.Code>()
        val device = launch {
            client.sse("http://127.0.0.1:$port${PairingWire.PATH}") {
                // The device stays on, reading its stream as a browser's EventSource does, until it
                // is cancelled. Not `incoming.first()`: Ktor's client hangs up the moment a collection
                // of `incoming` ends, so the device would leave as soon as it saw its code (bee#85).
                incoming.collect { event ->
                    code.complete(assertIs<PairingMessage.Code>(Json.decodeFromString(PairingMessage.serializer(), requireNotNull(event.data))))
                }
            }
        }
        val id = PairingId(withTimeout(10.seconds) { code.await() }.pairing)
        val pendingWhileOn = pairing.isPending(id)
        device.cancel()
        val endedAfterHangUp = withTimeout(5.seconds) {
            while (pairing.isPending(id)) delay(50.milliseconds)
            true
        }
        assertAll(
            { assertTrue(pendingWhileOn, "rig: the pairing waits while the device is on") },
            { assertTrue(endedAfterHangUp) },
        )
    }

    @Test
    fun thePairingChannelNeverUpgradesToAWebSocket() {
        val status = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(
                    (
                        "GET ${PairingWire.PATH} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                        ).encodeToByteArray(),
                )
                flush()
            }
            socket.getInputStream().bufferedReader().readLine()
        }
        assertFalse(status.startsWith("HTTP/1.1 101"), "the pairing channel answered a WebSocket upgrade: $status")
    }
}
