package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingSurfaces
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity
import kotlin.time.Duration.Companion.seconds
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.OK
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * A host behind Fly.io's TLS-terminating proxy, started as the `Dockerfile` starts it,
 * `--public-url https://bee.tractat.us/ --client-ip-header Fly-Client-IP` (bee#145), on a real
 * socket ([Handshakes]). Each dial writes the `Host`, `Origin` and forwarding headers a browser
 * behind Fly's proxy arrives with: `X-Forwarded-For: <client>, <app address>` and
 * `Fly-Client-IP: <client>` ([fly]). The proxy dials the host on a port of its own, so every dial
 * here names the public host with no port, whatever port the host bound. Every dial's TCP peer is
 * loopback, which grants nothing on a public host ([loopbackGrantsNothing]).
 *
 * A dial that must pass the site gate holds a paired device's [key], so a 403 is the site gate's.
 * A host that reads the rightmost `X-Forwarded-For` entry instead is [AppendingProxyHostTest]'s.
 */
class PublicHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w1-public-host-")
    private val config = HostConfig.fromArgs(
        listOf("--public-url", PUBLIC_URL, "--client-ip-header", "Fly-Client-IP", "--port", "0", "--data-dir", dataDir.toString()),
        home = Path.of("/nonexistent"),
        environment = { name -> FAKE_OWNER_KEY.takeIf { name == OwnerKey.ENV } },
    )
    private val host = runBlocking { BeeHost.start(scope, config) }
    private val calls = Handshakes(host.port)

    // kuilt's server looms read the dialler's fabric id from `peer`.
    private val board = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"
    private val claims = "${BeeHost.CLAIMS_PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"

    /** A key pairing minted, as a person typing a device's code mints one. */
    private val key: String = run {
        val ticket = assertIs<Opened.Ticket>(host.pairing.open(Caller.forwarded("198.51.100.200")))
        assertIs<Attempt.Admitted>(host.pairing.attempt(ticket.id, PeerIdentity.human("iain"), ticket.code)).key.value
    }

    @AfterTest
    fun stop() {
        host.close()
        scope.cancel()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun thePagesOwnHttpsOriginOnThePublicHostPasses() {
        assertAll(
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT)) },
            { assertEquals(SWITCHING, calls.upgrade("$claims&k=$key", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT)) },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", "BEE.Tractat.US", PUBLIC_ORIGIN, *CLIENT), "a host name has no case") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", "$PUBLIC_HOST:443", PUBLIC_ORIGIN, *CLIENT), "https's own port, spelled out") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", PUBLIC_HOST, null, *CLIENT), "the bridge sends no Origin") },
            { assertEquals(OK, calls.get(BoardId.PATH, PUBLIC_HOST, *CLIENT, SAME_ORIGIN)) },
            { assertEquals(OK, calls.get("/", PUBLIC_HOST, *CLIENT, CROSS_SITE, NAVIGATE, DOCUMENT), "a join link opened from chat") },
        )
    }

    @Test
    fun anotherOriginSchemeOrHostIsRefused() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$key", PUBLIC_HOST, "https://evil.example", *CLIENT), "another site") },
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$key", PUBLIC_HOST, "http://$PUBLIC_HOST", *CLIENT), "http:// for an https:// public URL") },
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$key", PUBLIC_HOST, "$PUBLIC_ORIGIN:${host.port}", *CLIENT), "the origin at the port the host bound") },
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$key", "evil.example", "https://evil.example", *CLIENT), "a rebound name") },
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$key", "$PUBLIC_HOST:${host.port}", PUBLIC_ORIGIN, *CLIENT), "the public name at another port than https's") },
            { assertEquals(FORBIDDEN, calls.get(PAIR, PUBLIC_HOST, *CLIENT, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "Fetch Metadata still holds") },
        )
    }

    /** Fly's health check dials the machine by the address it arrived on. */
    @Test
    fun aCallByTheAddressItArrivedOnStillPasses() {
        assertEquals(OK, calls.get(BoardId.PATH, "127.0.0.1:${host.port}"))
    }

    /**
     * A dial the proxy forwards from loopback, or one that says it was, is no local caller: it
     * needs a key, as one with no forwarding header does ([loopbackGrantsNothing]).
     */
    @Test
    fun aForwardedCallIsNeverLocal() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT), "a client, forwarded") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, *fly("127.0.0.1")), "a forwarded loopback address") },
            { assertEquals(FORBIDDEN, calls.upgrade(claims, PUBLIC_HOST, PUBLIC_ORIGIN, "Fly-Client-IP: 127.0.0.1"), "the client header alone") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "X-Forwarded-For: 203.0.113.7, 127.0.0.1"), "X-Forwarded-For alone, loopback rightmost") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "Forwarded: for=127.0.0.1"), "RFC 7239's header") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "X-Forwarded-For: 203.0.113.7, $APP", "Fly-Client-IP: unknown"), "a client header that names no address") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "Fly-Client-IP: "), "an empty one") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", PUBLIC_HOST, PUBLIC_ORIGIN, *fly("127.0.0.1")), "rig: a key admits it") },
        )
    }

    /**
     * The invite is the public URL, bare, and goes to any page the host admitted, since any
     * admitted person can admit anyone (Iain, 2026-11-08). Being on the machine grants nothing.
     */
    @Test
    fun anyAdmittedPageGetsTheBarePublicUrl() {
        val asOwner = calls.fetch("${InviteLinks.PATH}?k=$FAKE_OWNER_KEY", PUBLIC_HOST, *CLIENT, SAME_ORIGIN)
        val asPaired = calls.fetch("${InviteLinks.PATH}?k=$key", PUBLIC_HOST, *CLIENT, SAME_ORIGIN)
        val refusals = listOf(
            calls.fetch(InviteLinks.PATH, "127.0.0.1:${host.port}"),
            calls.fetch(InviteLinks.PATH, PUBLIC_HOST, *CLIENT, SAME_ORIGIN),
            calls.fetch("${InviteLinks.PATH}?k=${host.pairing.joinKey.value}", PUBLIC_HOST, *CLIENT, SAME_ORIGIN),
        )
        val bare = InviteLinks(emptyList(), PUBLIC_URL, InviteLinks.Joins.Anywhere)
        assertAll(
            { assertEquals(OK to bare, asOwner.first to Json.decodeFromString(InviteLinks.serializer(), asOwner.second), "the owner") },
            { assertEquals(OK to bare, asPaired.first to Json.decodeFromString(InviteLinks.serializer(), asPaired.second), "a paired device, not the owner") },
            { assertFalse("${PairingWire.KEY_PARAM}=" in asPaired.second, "no key in the link") },
            { assertEquals(listOf(FORBIDDEN, FORBIDDEN, FORBIDDEN), refusals.map { it.first }, "loopback with no key, a forwarded caller with none, the join key") },
        )
    }

    /** Only paired device keys write on a hosted board (bee#159): the join link's key admits nothing. */
    @Test
    fun theJoinKeyAdmitsNothing() = runBlocking {
        val join = host.pairing.joinKey.value
        val mcp = HttpClient(CIO).use { http ->
            http.post("http://127.0.0.1:${host.port}${BeeHost.MCP_PATH}?k=$join") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{},"clientInfo":{"name":"raw","version":"0"}}}""",
                )
            }.status
        }
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=$join", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT), "the board") },
            { assertEquals(FORBIDDEN, calls.upgrade("$claims&k=$join", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT), "claims") },
            { assertEquals(HttpStatusCode.Forbidden, mcp, "MCP") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT), "rig: a paired key admits the same dial") },
        )
    }

    /**
     * Product invariant 2, on the real host: with nobody attached who can admit, a pairing is
     * refused at once and nothing goes on the board; once a person is attached, a dial gets a
     * code and its request goes up.
     */
    @Test
    fun aPairingWithNobodyHereIsRefusedAndPostsNothing() = runBlocking {
        val alone = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, *CLIENT).use { it.message() }
        delay(Pairing.DWELL + 1.seconds)
        val postedAlone = host.state.value.pairingRequests()
        host.attachSomeone()
        val (device, withSomeone) = calls.pairOnceSomeoneIsHere(PAIR, PUBLIC_HOST, SAME_ORIGIN, *fly("203.0.113.51"))
        device.use {
            val posted = withTimeout(10.seconds) { host.state.first { it.pairingRequests().isNotEmpty() } }.pairingRequests()
            assertAll(
                { assertEquals(PairingMessage.Ended(PairingEnd.NobodyHere), alone) },
                { assertEquals(emptyList(), postedAlone, "nothing waited on the board") },
                { assertIs<PairingMessage.Code>(withSomeone, "the positive control: a person attached") },
                { assertEquals(1, posted.size) },
            )
        }
    }

    /** Nobody is on loopback on a hosted machine, and a call from there gets nothing without a key. */
    @Test
    fun loopbackGrantsNothing() {
        val loopback = "127.0.0.1:${host.port}"
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board, loopback), "the board, with no key and no forwarded header") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN), "the public name, no forwarding header") },
            { assertEquals(FORBIDDEN, calls.upgrade(claims, loopback), "claims") },
            { assertEquals(FORBIDDEN, calls.get(BeeHost.MCP_PATH, loopback), "MCP") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$FAKE_OWNER_KEY", loopback), "rig: the owner key admits the same dial") },
        )
    }

    /** The owner opens `https://bee.tractat.us/#k=<owner key>`, and the page's sockets carry it. */
    @Test
    fun theOwnerKeyAdmitsTheBoardAndClaims() {
        assertAll(
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$FAKE_OWNER_KEY", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT)) },
            { assertEquals(SWITCHING, calls.upgrade("$claims&k=$FAKE_OWNER_KEY", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT)) },
            { assertEquals(FORBIDDEN, calls.upgrade("$board&k=${FAKE_OWNER_KEY.dropLast(1)}x", PUBLIC_HOST, PUBLIC_ORIGIN, *CLIENT), "a near miss") },
        )
    }

    /**
     * Two clients behind the one proxy each get a pairing turn of their own: the caller is the
     * client Fly names, not the app's address every call carries rightmost. A host that read
     * `X-Forwarded-For` instead would refuse the second client as the first.
     */
    @Test
    fun pairingGivesEachClientBehindTheProxyItsOwnTurn() = runBlocking {
        host.attachSomeone()
        val (first, opened) = calls.pairOnceSomeoneIsHere(PAIR, PUBLIC_HOST, SAME_ORIGIN, *fly("198.51.100.1"))
        first.use {
            val anotherClient = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, *fly("198.51.100.9")).use { it.message() }
            val sameClient = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 203.0.113.7, 198.51.100.1, $APP", "Fly-Client-IP: 198.51.100.1").use { it.message() }
            assertAll(
                { assertIs<PairingMessage.Code>(opened) },
                { assertIs<PairingMessage.Code>(anotherClient, "another client, behind the same proxy") },
                { assertEquals(PairingMessage.Ended(PairingEnd.TooSoon), sameClient, "one client, whatever it put in X-Forwarded-For") },
            )
        }
    }

    /**
     * A client header that names no address leaves the caller its TCP peer, the proxy's: neither
     * the header's text nor the rightmost `X-Forwarded-For` entry, which differ between these two.
     */
    @Test
    fun aClientHeaderThatNamesNoAddressFallsBackToTheTcpPeer() = runBlocking {
        host.attachSomeone()
        val (first, opened) = calls.pairOnceSomeoneIsHere(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 198.51.100.1, $APP", "Fly-Client-IP: unknown")
        first.use {
            val again = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 198.51.100.9, 192.0.2.2", "Fly-Client-IP: 198.51.100.9, 192.0.2.2").use { it.message() }
            assertAll(
                { assertIs<PairingMessage.Code>(opened) },
                { assertEquals(PairingMessage.Ended(PairingEnd.TooSoon), again, "both the proxy's, by their TCP peer") },
            )
        }
    }

    /** A forwarded loopback address is limited like any client: it is no local caller. */
    @Test
    fun pairingLimitsAForwardedLoopbackAddress() = runBlocking {
        host.attachSomeone()
        val (first, opened) = calls.pairOnceSomeoneIsHere(PAIR, PUBLIC_HOST, SAME_ORIGIN, *fly("127.0.0.1"))
        first.use {
            val again = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, *fly("127.0.0.1")).use { it.message() }
            assertAll(
                { assertIs<PairingMessage.Code>(opened) },
                { assertEquals(PairingMessage.Ended(PairingEnd.TooSoon), again) },
            )
        }
    }

    private fun BoardState.pairingRequests(): List<String> = (openSurfaces() + closedSurfaces()).filter(PairingSurfaces::isPairing)

    private companion object {
        const val PUBLIC_URL = "https://bee.tractat.us/"
        const val PUBLIC_HOST = "bee.tractat.us"
        const val PUBLIC_ORIGIN = "https://bee.tractat.us"

        /** The app's own address, which Fly's proxy puts rightmost in `X-Forwarded-For` for every client. */
        const val APP = "192.0.2.1"

        /** The headers Fly's proxy forwards a call from [client] with. */
        fun fly(client: String): Array<String> = arrayOf("X-Forwarded-For: $client, $APP", "Fly-Client-IP: $client")

        /** A client on the internet, as Fly's proxy forwards it. */
        val CLIENT = fly("203.0.113.50")
        const val PAIR = "${PairingWire.PATH}?${PairingWire.SCREEN_PARAM}=probe"
        const val SAME_ORIGIN = "Sec-Fetch-Site: same-origin"
        const val CROSS_SITE = "Sec-Fetch-Site: cross-site"
        const val NAVIGATE = "Sec-Fetch-Mode: navigate"
        const val DOCUMENT = "Sec-Fetch-Dest: document"
    }
}
