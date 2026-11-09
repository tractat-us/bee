package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
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
 * TCP peer ([PeerAddress.DIRECT]), whose loopback test is [onlyLoopbackLiteralsAreLoopback]. A
 * loopback peer arrives at `127.0.0.1` and any other at the laptop's LAN address [LAPTOP], both on
 * [PORT] ([TEST_ARRIVALS]); each call's `Host` names where it arrived unless a test says otherwise.
 * This host goes by [LAN_NAME] too, as `--lan` would name it. The site gate on a real socket, from
 * loopback, is [SiteGateTest]'s.
 *
 * The test engine's WebSocket client doesn't say why a handshake failed, and won't send an
 * `Upgrade` header by hand. So each gated route also gets a plain [PROBE] route below it, on the
 * same route node the gate is installed on: a refused dial is one whose WebSocket failed and whose
 * probe, with the same peer and query, got the gate's 403. Anything else is a broken rig.
 */
class LanKeyGateTest {
    private val pairing = Pairing({ Clock.System.now() }, SecureSecrets(SecureRandom()), memoryKeys())

    private fun gated(test: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            mountEndpoints(BOARD_ID, pairing, SiteRule(setOf(LAN_NAME)), TEST_PEERS, TEST_ARRIVALS, PairingDesk(pairing, CompletableDeferred(), Pairing.DWELL, clock = { Clock.System.now() }), Invitation.Closed)
            routing {
                for (gated in listOf(BoardRelay.PATH, BeeHost.CLAIMS_PATH)) {
                    route(gated) { get(PROBE) { call.respondText("through the gate") } }
                }
            }
        }
        test(
            createClient {
                install(WebSockets)
                install(SSE)
            },
        )
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
        val joinKey = pairing.joinKey
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

    /** A key admits a device, not a board: a keyed socket naming another board is refused (bee#46). */
    @Test
    fun aKeyedSocketNamingAnotherBoardIsRefused() = gated { client ->
        val key = pairing.joinKey.value
        val stale = BoardId.mint()
        val right = client.dial("$BOARD&k=$key", LAN)
        val boardStale = client.dial("${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=$stale&k=$key", LAN)
        val claimsNone = client.dial("${BeeHost.CLAIMS_PATH}?peer=probe&k=$key", LAN)
        val loopbackStale = client.dial("${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=$stale", "127.0.0.1")
        assertAll(
            { assertEquals(Dial.Upgraded, right, "rig: this board, keyed") },
            { assertEquals(Dial.Refused, boardStale) },
            { assertEquals(Dial.Refused, claimsNone, "no board named") },
            { assertEquals(Dial.Refused, loopbackStale, "loopback needs no key, and still names its board") },
        )
    }

    /** The page, the board's id and the pairing channel are what a keyless device needs, so all stay open to it. */
    @Test
    fun pageAndPairingNeedNoKey() = gated { client ->
        val page = client.get("/") { fromLan() }.status
        val boardId = client.get(BoardId.PATH) { fromLan() }.bodyAsText()
        var shown: PairingMessage? = null
        var pending = false
        client.sse(PairingWire.PATH, request = { fromLan() }) {
            shown = Json.decodeFromString(PairingMessage.serializer(), requireNotNull(incoming.first().data))
            pending = pairing.isPending(PairingId((shown as PairingMessage.Code).pairing))
        }
        val code = assertIs<PairingMessage.Code>(shown)
        assertAll(
            { assertNotEquals(HttpStatusCode.Forbidden, page) },
            { assertEquals(BOARD_ID.value, boardId) },
            { assertTrue(code.code.matches(Regex("[0-9]{6}")), "a six-digit code: ${code.code}") },
            { assertTrue(pending, "the code names a live pairing") },
        )
    }

    /** A keyed LAN page, from its own origin, by the host's LAN name or its LAN address (bee#45). */
    @Test
    fun theBoardPageFromItsOwnOriginIsAdmittedOnTheLan() = gated { client ->
        val key = pairing.joinKey.value
        val byName = "$LAN_NAME:$PORT"
        val byAddress = "$LAPTOP:$PORT"
        val boardByName = client.dial("$BOARD&k=$key", LAN, host = byName, origin = "http://$byName")
        val claimsByName = client.dial("$CLAIMS&k=$key", LAN, host = byName, origin = "http://$byName")
        val boardByAddress = client.dial("$BOARD&k=$key", LAN, host = byAddress, origin = "http://$byAddress")
        val upperCase = client.dial("$BOARD&k=$key", LAN, host = byName.uppercase(), origin = "http://$byName")
        assertAll(
            { assertEquals(Dial.Upgraded, boardByName) },
            { assertEquals(Dial.Upgraded, claimsByName) },
            { assertEquals(Dial.Upgraded, boardByAddress) },
            { assertEquals(Dial.Upgraded, upperCase, "a host name has no case") },
        )
    }

    /** Every dial here holds a good key, so a refusal is the site gate's (bee#45). */
    @Test
    fun aLanCallFromAnotherSiteOrByAnotherNameIsRefused() = gated { client ->
        val key = pairing.joinKey.value
        val byName = "$LAN_NAME:$PORT"
        val crossSite = client.dial("$BOARD&k=$key", LAN, host = byName, origin = "http://evil.example")
        val rebound = client.dial("$BOARD&k=$key", LAN, host = "evil.example:$PORT", origin = "http://evil.example:$PORT")
        val otherAddress = client.dial("$BOARD&k=$key", LAN, host = "192.168.1.9:$PORT")
        val loopbackFromLan = client.dial("$BOARD&k=$key", LAN, host = "127.0.0.1:$PORT")
        val pairingRebound = client.get(PairingWire.PATH) {
            header(TEST_PEER, LAN)
            header(HttpHeaders.Host, "evil.example:$PORT")
        }.status
        assertAll(
            { assertEquals(Dial.Refused, crossSite, "another site's page") },
            { assertEquals(Dial.Refused, rebound, "a rebound name") },
            { assertEquals(Dial.Refused, otherAddress, "an address the call didn't arrive at") },
            { assertEquals(Dial.Refused, loopbackFromLan, "a loopback address the call didn't arrive at") },
            { assertEquals(HttpStatusCode.Forbidden, pairingRebound, "pairing, by a rebound name") },
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

    /** From [LAN], to the laptop's LAN address. */
    private fun HttpRequestBuilder.fromLan() {
        header(TEST_PEER, LAN)
        header(HttpHeaders.Host, "$LAPTOP:$PORT")
    }

    /**
     * Dials [path] from [peer], naming the host [host] and sending [origin] if any: [Dial.Upgraded]
     * if the WebSocket opened, [Dial.Refused] on a 403. [host] is where the call arrived, by default.
     */
    private suspend fun HttpClient.dial(
        path: String,
        peer: String,
        host: String = arrivalOf(peer).let { "${it.address}:${it.port}" },
        origin: String? = null,
    ): Dial {
        val headers: HttpRequestBuilder.() -> Unit = {
            header(TEST_PEER, peer)
            header(HttpHeaders.Host, host)
            origin?.let { header(HttpHeaders.Origin, it) }
        }
        return try {
            webSocket(path, request = headers) { close() }
            Dial.Upgraded
        } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
            val (route, query) = path.split('?', limit = 2)
            val status = get("$route/$PROBE?$query", headers).status
            check(status == HttpStatusCode.Forbidden) { "the dial failed, but its probe got $status, not the gate's 403: $failed" }
            Dial.Refused
        }
    }

    private companion object {
        const val TEST_PEER = "X-Bee-Test-Peer"
        const val PROBE = "gate-probe"
        const val LAN = "192.168.1.20"

        /** The laptop's own LAN address, its LAN name, and the port it serves on. */
        const val LAPTOP = "192.168.1.5"
        const val LAN_NAME = "tractatus.local"
        const val PORT = 8233

        // kuilt's server looms read the dialler's fabric id from `peer`.
        val BOARD_ID = BoardId.mint()
        val BOARD = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=$BOARD_ID"
        val CLAIMS = "${BeeHost.CLAIMS_PATH}?peer=probe&${BoardId.PARAM}=$BOARD_ID"

        val TEST_PEERS = PeerAddress { call -> requireNotNull(call.request.headers[TEST_PEER]) { "rig: no $TEST_PEER header" } }

        /** A loopback peer reaches the laptop at loopback; any other, at its LAN address. */
        fun arrivalOf(peer: String) = Arrival(if (isLoopback(peer)) "127.0.0.1" else LAPTOP, PORT)

        val TEST_ARRIVALS = ArrivalAddress { call -> arrivalOf(TEST_PEERS.of(call)) }
    }
}
