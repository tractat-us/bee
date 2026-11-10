package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import us.tractat.kuilt.test.assertAll
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Pairing on a real Netty server, from loopback: plain requests and complete responses, never a
 * stream or a WebSocket, so a keyless device has no inbound frame parser to feed (the host reads
 * nothing from it but a bounded token). The dwell here is longer than the test, so no request
 * goes on the board.
 */
class PairingChannelTest {
    private val clock = { Clock.System.now() }
    private val pairing = Pairing(clock, SecureSecrets(SecureRandom()), memoryKeys())
    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        mountEndpoints(BoardId.mint(), pairing, SiteRule(emptySet()), PeerAddress.DIRECT, ArrivalAddress.DIRECT, PairingDesk(pairing, CompletableDeferred(), 10.minutes, clock, Admitters.WHOEVER_COMES), Invitation.Closed)
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }
    private val client = HttpClient(CIO)

    @AfterTest
    fun stop() {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    /**
     * The poll path on the real server (bee#209): plain requests and complete responses, no stream,
     * so a proxy that holds a stream back passes them. Only the token `POST /pair` gave answers.
     */
    @Test
    fun aDevicePairsByPollAndOnlyItsTokenAnswers() = runBlocking {
        val base = "http://127.0.0.1:$port"
        val opening = client.post(base + PairingWire.PATH)
        val opened = assertIs<PairingPoll.Opened>(Json.decodeFromString(PairingPoll.serializer(), opening.bodyAsText()))
        val code = opened.code
        val polled = client.post(base + PairingWire.POLL_PATH) { setBody(opened.token) }
        val polledMessage = Json.decodeFromString(PairingMessage.serializer(), polled.bodyAsText())
        val byPairingId = client.post(base + PairingWire.POLL_PATH) { setBody(code.pairing) }
        assertAll(
            { assertEquals(HttpStatusCode.OK, opening.status) },
            { assertEquals("no-store", opening.headers[HttpHeaders.CacheControl]) },
            { assertTrue(pairing.isPending(PairingId(code.pairing)), "the pairing waits on its polls") },
            { assertEquals(code, polledMessage) },
            { assertEquals("no-store", polled.headers[HttpHeaders.CacheControl]) },
            { assertEquals(HttpStatusCode.NotFound, byPairingId.status, "the pairing id, on the board for all, is no token") },
        )
    }

    /**
     * Anyone may poll, so a poll's body is bounded before it is read: one that says it is too long,
     * or turns out too long with no length given, is refused with a 413, never cut down to a token.
     */
    @Test
    fun aPollBodyPastATokensLengthIsRefused() = runBlocking {
        val url = "http://127.0.0.1:$port${PairingWire.POLL_PATH}"
        val declared = client.post(url) { setBody("x".repeat(1_000_000)) }
        val chunked = client.post(url) {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(ByteArray(200) { 'x'.code.toByte() })
                },
            )
        }
        val token = client.post(url) { setBody("x".repeat(43)) }
        assertAll(
            { assertEquals(HttpStatusCode.PayloadTooLarge, declared.status, "a Content-Length past the bound") },
            { assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status, "a chunked body past the bound") },
            { assertEquals(HttpStatusCode.NotFound, token.status, "the positive control: a token's length is read, and isn't one") },
        )
    }

    /**
     * Pairing has one transport (bee#211): a `GET` asking for an event stream, as a browser's
     * `EventSource` does, gets a client error. Only the status line is read, so a stream that
     * stays open answers `200` here rather than hanging the test.
     */
    @Test
    fun aGetOfThePairingPathIsNoStream() {
        val status = Handshakes(port).get(PairingWire.PATH, "127.0.0.1:$port", "Accept: text/event-stream")
        assertTrue(status.toInt() in CLIENT_ERRORS, "no route answers it: $status")
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

    private companion object {
        val CLIENT_ERRORS = 400..499
    }
}
