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
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
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
import kotlin.time.Clock
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
            val link = HostAddress.parse("http://127.0.0.1:${host.port}/?k=${host.pairing.mintJoinKey().value}")
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

    private suspend fun startHost() = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir), peers = { ANOTHER_MACHINE })

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
        suspend fun claimsOutcome(): Any = merge(claims.link.filterNotNull(), flow { emit(claimsRefused.await()) }).first()
    }

    private companion object {
        /** A LAN address, not loopback: where the gate believes every call comes from. */
        const val ANOTHER_MACHINE = "192.168.1.20"
        val TIMEOUT = 15.seconds
        const val ONLINE = "online"
        const val REFUSED = "refused"

        fun refused(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any { "403" in it.message.orEmpty() }
    }
}
