package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.core.askBoardId
import us.tractat.bee.core.pinnedTo
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A bridge on another machine, given the host's join link as its `BEE_HOST`: a real [BeeHost], and
 * a board replica and a claims link built from a [HostAddress] the way `bee-mcp` builds them, over
 * CIO, the bridge's own client.
 *
 * The test has one machine, so the host's socket gate reads every call's peer as [ANOTHER_MACHINE]
 * (an injected [PeerAddress]): a dial needs a key exactly as one off the laptop would. Everything
 * else is real, the site gate included, which sees what the bridge sends: a `Host` naming the
 * address it dialled, and no `Origin` or Fetch Metadata. A refusal is a handshake answered 403,
 * which only a gate gives; a host that isn't there fails to connect at all.
 */
class BridgeJoinLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("w1-bridge-join-link-")
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val clock = { Clock.System.now() }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun theJoinLinkAdmitsTheBridgeOnBoardAndClaims() = runBlocking {
        val host = startHost()
        try {
            val link = HostAddress.parse("http://127.0.0.1:${host.port}/?k=${host.pairing.joinKey.value}")
            val bridge = Bridge(link)
            val board = withTimeout(TIMEOUT) { bridge.boardOutcome() }
            val claims = assertIs<ClaimsLink>(withTimeout(TIMEOUT) { bridge.claimsOutcome() }, "the claims link, not a refusal")
            val won = claims.client.claim(ClaimTarget("record:join-link:1"), PeerIdentity.agent("ada", Random(1)))
            assertAll(
                { assertEquals(ONLINE, board, "the board replica") },
                { assertIs<ClaimOutcome.Won>(won, "the claims link commits a claim") },
                { assertEquals(host.boardId, bridge.pin.board.value, "the board, asked over HTTP") },
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun withoutItsKeyTheBridgeIsRefusedOnBoardAndClaims() = runBlocking {
        val host = startHost()
        try {
            val root = HostAddress.parse("http://127.0.0.1:${host.port}/")
            val bridge = Bridge(root)
            val board = withTimeout(TIMEOUT) { bridge.boardOutcome() }
            val claims = withTimeout(TIMEOUT) { bridge.claimsOutcome() }
            assertAll(
                { assertEquals(REFUSED, board, "the board socket's gate answers 403") },
                { assertIs<Throwable>(claims, "the claims socket's gate answers 403, not a link") },
                { assertFalse(bridge.board.online.value, "the board replica never came online") },
                { assertEquals(host.boardId, bridge.pin.board.value, "rig: /board-id is open, so the bridge learned the board") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * The demo's last beat (bee#106): the host stops and starts again on the same data dir and
     * port, with a bridge on the join link and a paired device out on the network. The bridge
     * redials on its own, with the link it was given, and reads and writes the board again; the
     * device's key still admits. The control is the same restart with `--new-link`: the gate then
     * refuses the bridge, so a reconnect here is the kept key's doing, not a gate that let anyone in.
     */
    @Test
    fun aBridgeOnTheJoinLinkAndAPairedDeviceAreAdmittedAfterARestart() = runBlocking {
        val first = startHost()
        val port = first.port
        val ticket = assertIs<Opened.Ticket>(first.pairing.open(Caller.byPeer(ANOTHER_MACHINE)))
        val paired = assertIs<Attempt.Admitted>(first.pairing.attempt(ticket.id, PeerIdentity.human("iain"), ticket.code)).key
        val joinKey = first.pairing.joinKey
        val bridge = Bridge(HostAddress.parse("http://127.0.0.1:$port/?k=${joinKey.value}"))
        val claims = assertIs<ClaimsLink>(withTimeout(TIMEOUT) { bridge.claimsOutcome() }, "rig: the claims link, before the restart")
        assertEquals(ONLINE, withTimeout(TIMEOUT) { bridge.boardOutcome() }, "rig: the bridge is on the board before the restart")
        first.close()
        withTimeout(TIMEOUT) { bridge.board.online.first { !it } }

        val second = startHost(port)
        try {
            assertEquals(ONLINE, withTimeout(RESTART_TIMEOUT) { bridge.boardOutcome() }, "the bridge redials, on the link it was given")
            second.mutate { it.openSurface(second.author, FROM_HOST, meta("After the restart"), listOf(text("From the host")), JsonObject(emptyMap())) }
            val readsTheHost = withTimeout(RESTART_TIMEOUT) { bridge.board.state.first { it.surfaceDoc(FROM_HOST) != null } }
            bridge.board.mutate { it.openSurface(bridge.author, FROM_BRIDGE, meta("From the bridge"), listOf(text("From the bridge")), JsonObject(emptyMap())) }
            val hostHasTheBridgesSurface = withTimeout(TIMEOUT) { second.state.first { FROM_BRIDGE in it.openSurfaces() } }
            val relinked = withTimeout(RESTART_TIMEOUT) { bridge.claims.link.filterNotNull().first { it !== claims } }
            val won = relinked.client.claim(ClaimTarget("record:join-link:after-restart"), PeerIdentity.agent("ada", Random(1)))
            val handshakes = Handshakes(port)
            val board = "${BoardRelay.PATH}?peer=room&${BoardId.PARAM}=${second.boardId}&k="
            val pairedKey = handshakes.upgrade(board + paired.value)
            val strangerKey = handshakes.upgrade(board + "not-a-key")
            assertAll(
                { assertEquals(joinKey, second.pairing.joinKey, "the same join link") },
                { assertEquals(first.boardId, second.boardId, "rig: the same board") },
                { assertNotNull(readsTheHost.surfaceDoc(FROM_HOST), "the bridge reads what the restarted host draws") },
                { assertTrue(FROM_BRIDGE in hostHasTheBridgesSurface.openSurfaces(), "the bridge's surface reaches the restarted host") },
                { assertIs<ClaimOutcome.Won>(won, "the bridge's claims link redials and commits a claim") },
                { assertEquals(Handshakes.SWITCHING, pairedKey, "a device paired before the restart is admitted") },
                { assertEquals(Handshakes.FORBIDDEN, strangerKey, "rig: the gate still refuses a key it never minted") },
            )
        } finally {
            second.close()
        }

        withTimeout(TIMEOUT) { bridge.board.online.first { !it } }
        val rotated = startHost(port, KeyReset.NewLink)
        try {
            val outcome = withTimeout(RESTART_TIMEOUT) { bridge.boardOutcome() }
            val pairedKey = Handshakes(port).upgrade("${BoardRelay.PATH}?peer=room&${BoardId.PARAM}=${rotated.boardId}&k=${paired.value}")
            assertAll(
                { assertEquals(REFUSED, outcome, "control: after --new-link the old link's bridge is refused") },
                { assertNotEquals(joinKey, rotated.pairing.joinKey, "control: a new join key") },
                { assertEquals(Handshakes.SWITCHING, pairedKey, "--new-link keeps paired devices") },
            )
        } finally {
            rotated.close()
        }

        val forgot = startHost(port, KeyReset.ForgetDevices)
        try {
            val pairedKey = Handshakes(port).upgrade("${BoardRelay.PATH}?peer=room&${BoardId.PARAM}=${forgot.boardId}&k=${paired.value}")
            assertEquals(Handshakes.FORBIDDEN, pairedKey, "--forget-devices: the paired device is refused")
        } finally {
            forgot.close()
        }
    }

    private suspend fun startHost(port: Int = 0, reset: KeyReset = KeyReset.Keep) =
        BeeHost.start(scope, HostConfig(port = port, dataDir = dir, keyReset = reset), peers = { ANOTHER_MACHINE })

    /** A bridge's board replica and claims connection on [host], as `bee-mcp`'s `main` builds them. */
    private inner class Bridge(host: HostAddress) {
        val author = ReplicaId("mcp-join-link")
        val pin = BoardPin.learnedFrom { http.askBoardId(host) }
        val board = BoardClient(
            author,
            SpokeLoom(http, Dispatchers.Default, scope, author.value, Random.Default, clock).pinnedTo(pin),
            Rendezvous.Existing(WebSocketAdvertisement(host.socket(BoardRelay.PATH), PeerId("bee-host"), "board")),
            scope,
            clock,
        ).also { it.connect() }

        /** The first refusal the claims connection hears, from the gate. */
        val claimsRefused = CompletableDeferred<Throwable>()

        val claims = ClaimsConnection(
            scope = scope,
            nextNode = { NodeId("mcp-join-link-${Random.Default.nextLong().toULong()}") },
            open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, host, pin, linkScope, clock, Random.Default) },
            onFailure = { failure -> if (refused(failure)) claimsRefused.complete(failure) },
            retryDelay = { 200.milliseconds },
        )

        /**
         * [ONLINE] once the board replica is online, [REFUSED] once its socket's gate refuses it:
         * whichever comes first, so a test that meets the other fails saying so, not as a timeout.
         */
        suspend fun boardOutcome(): String = merge(
            board.online.filter { it }.map { ONLINE },
            board.lastFailure.filterNotNull().filter(::refused).map { REFUSED },
        ).first()

        /** The claims link once one proves itself, or the gate's refusal of it. */
        suspend fun claimsOutcome(): Any = merge<Any>(claims.link.filterNotNull(), flow { emit(claimsRefused.await()) }).first()
    }

    private companion object {
        /** A LAN address, not loopback: where the gate believes every call comes from. */
        const val ANOTHER_MACHINE = "192.168.1.20"
        val TIMEOUT = 15.seconds
        const val ONLINE = "online"
        const val REFUSED = "refused"

        /** A restart: the bridge's backoff may have grown to its 5 s cap while no host answered. */
        val RESTART_TIMEOUT = 30.seconds
        const val FROM_HOST = "drawn-after-the-restart"
        const val FROM_BRIDGE = "drawn-by-the-bridge-after-the-restart"

        fun text(text: String): JsonObject = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", text)
        }

        fun meta(title: String) = SurfaceMeta(title, NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-08T12:00:00Z"))

        fun refused(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any { "403" in it.message.orEmpty() }
    }
}
