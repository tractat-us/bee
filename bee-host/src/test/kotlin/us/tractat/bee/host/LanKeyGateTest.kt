package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.test.assertAll
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The key gate on the host's real endpoints (spec §5.7), in memory: [mountEndpoints] mounts the
 * board and claims routes exactly as `BeeHost` does, on Ktor's test engine.
 *
 * The test engine has no sockets, so every request names the address it comes from in
 * [TEST_PEER], and the gate reads it through an injected [PeerAddress]. Production reads the
 * TCP peer ([PeerAddress.DIRECT]), whose loopback test is [onlyLoopbackLiteralsAreLoopback].
 *
 * The test engine's WebSocket client doesn't say why a handshake failed, and won't send an
 * `Upgrade` header by hand. So each gated route also gets a plain [PROBE] route below it, on the
 * same route node the gate is installed on: a refused dial is one whose WebSocket failed and whose
 * probe, with the same peer and query, got the gate's 403. Anything else is a broken rig.
 */
class LanKeyGateTest {
    private val pairing = Pairing({ Clock.System.now() }, SecureSecrets(SecureRandom()))

    private fun gated(test: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            mountEndpoints(pairing, TEST_PEERS, PairingDesk(pairing, CompletableDeferred(), Pairing.DWELL, clock = { Clock.System.now() }))
            routing {
                for (gated in listOf(BoardRelay.PATH, BeeHost.CLAIMS_PATH)) {
                    route(gated) { get(PROBE) { call.respondText("through the gate") } }
                }
            }
        }
        test(createClient { install(WebSockets) })
    }

    @Test
    fun nonLoopbackWithoutKeyIsRefusedOnBoardAndClaims() = gated { client ->
        val board = client.dial(BOARD, LAN)
        val claims = client.dial(CLAIMS, LAN)
        assertAll(
            { assertEquals(Dial.Refused, board) },
            { assertEquals(Dial.Refused, claims) },
        )
    }

    @Test
    fun loopbackNeedsNoKey() = gated { client ->
        val boardV4 = client.dial(BOARD, "127.0.0.1")
        val claimsV4 = client.dial(CLAIMS, "127.0.0.1")
        val boardV6 = client.dial(BOARD, "::1")
        assertAll(
            { assertEquals(Dial.Upgraded, boardV4) },
            { assertEquals(Dial.Upgraded, claimsV4) },
            { assertEquals(Dial.Upgraded, boardV6) },
        )
    }

    @Test
    fun urlKeyAdmits() = gated { client ->
        val ticket = assertIs<Opened.Ticket>(pairing.open(LAN))
        val key = assertIs<Attempt.Admitted>(pairing.attempt(ticket.id, PeerIdentity.human("iain"), ticket.code)).key
        val joinKey = pairing.mintJoinKey()
        val boardKeyed = client.dial("$BOARD&k=${key.value}", LAN)
        val claimsKeyed = client.dial("$CLAIMS&k=${key.value}", LAN)
        val boardJoinLink = client.dial("$BOARD&k=${joinKey.value}", LAN)
        assertAll(
            { assertEquals(Dial.Upgraded, boardKeyed) },
            { assertEquals(Dial.Upgraded, claimsKeyed) },
            { assertEquals(Dial.Upgraded, boardJoinLink, "the printed join link's key") },
        )
    }

    @Test
    fun wrongUrlKeyIsRefused() = gated { client ->
        val ticket = assertIs<Opened.Ticket>(pairing.open(LAN))
        val key = assertIs<Attempt.Admitted>(pairing.attempt(ticket.id, PeerIdentity.human("iain"), ticket.code)).key
        val boardRight = client.dial("$BOARD&k=${key.value}", LAN)
        // The right key with its last character changed: a near miss, never the key itself.
        val nearMiss = key.value.dropLast(1) + if (key.value.last() == 'x') 'y' else 'x'
        val boardWrong = client.dial("$BOARD&k=$nearMiss", LAN)
        val claimsWrong = client.dial("$CLAIMS&k=$nearMiss", LAN)
        val boardEmpty = client.dial("$BOARD&k=", LAN)
        val boardCode = client.dial("$BOARD&k=${ticket.code}", LAN)
        assertAll(
            { assertEquals(Dial.Upgraded, boardRight, "rig: the right key admits") },
            { assertEquals(Dial.Refused, boardWrong) },
            { assertEquals(Dial.Refused, claimsWrong) },
            { assertEquals(Dial.Refused, boardEmpty) },
            { assertEquals(Dial.Refused, boardCode, "a pairing code is not a key") },
        )
    }

    /** The page and the pairing socket are what a keyless device needs, so both stay open to it. */
    @Test
    fun pageAndPairingNeedNoKey() = gated { client ->
        val page = client.get("/") { header(TEST_PEER, LAN) }.status
        var shown: PairingMessage? = null
        var pending = false
        client.webSocket(PairingWire.PATH, request = { header(TEST_PEER, LAN) }) {
            shown = Json.decodeFromString(PairingMessage.serializer(), (incoming.receive() as Frame.Text).readText())
            pending = pairing.isPending(PairingId((shown as PairingMessage.Code).pairing))
            close()
        }
        val code = assertIs<PairingMessage.Code>(shown)
        assertAll(
            { assertNotEquals(HttpStatusCode.Forbidden, page) },
            { assertTrue(code.code.matches(Regex("[0-9]{6}")), "a six-digit code: ${code.code}") },
            { assertTrue(pending, "the code names a live pairing") },
        )
    }

    @Test
    fun onlyLoopbackLiteralsAreLoopback() {
        assertAll(
            { assertTrue(isLoopback("127.0.0.1")) },
            { assertTrue(isLoopback("127.3.2.1")) },
            { assertTrue(isLoopback("::1")) },
            { assertTrue(isLoopback("0:0:0:0:0:0:0:1")) },
            { assertFalse(isLoopback("192.168.1.20")) },
            { assertFalse(isLoopback("fe80::1")) },
            { assertFalse(isLoopback("0.0.0.0")) },
            // A name is never read as loopback: it would cost a DNS lookup, and a name can lie.
            { assertFalse(isLoopback("localhost")) },
            { assertFalse(isLoopback("")) },
        )
    }

    private enum class Dial { Upgraded, Refused }

    /** Dials [path] from [peer]: [Dial.Upgraded] if the WebSocket opened, [Dial.Refused] on a 403. */
    private suspend fun HttpClient.dial(path: String, peer: String): Dial = try {
        webSocket(path, request = { header(TEST_PEER, peer) }) { close() }
        Dial.Upgraded
    } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
        val (route, query) = path.split('?', limit = 2)
        val status = get("$route/$PROBE?$query") { header(TEST_PEER, peer) }.status
        check(status == HttpStatusCode.Forbidden) { "the dial failed, but its probe got $status, not the gate's 403: $failed" }
        Dial.Refused
    }

    private companion object {
        const val TEST_PEER = "X-Bee-Test-Peer"
        const val PROBE = "gate-probe"
        const val LAN = "192.168.1.20"

        // kuilt's server looms read the dialler's fabric id from `peer`.
        const val BOARD = "${BoardRelay.PATH}?peer=probe"
        const val CLAIMS = "${BeeHost.CLAIMS_PATH}?peer=probe"

        val TEST_PEERS = PeerAddress { call -> requireNotNull(call.request.headers[TEST_PEER]) { "rig: no $TEST_PEER header" } }
    }
}
