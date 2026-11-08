package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.pinnedTo
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.Departure
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The join over real loopback sockets: a [BeeHost], and a bridge's three pieces (a board replica,
 * a claims learner and its presence) built the way `bee-mcp` builds them. Runs only with
 * `-Pbee.realsocket.tests=true`.
 */
class JoinSmokeTest {
    /**
     * A bridge killed while holding a claim shows departed `Crashed` on the board, its claim
     * orphaned, within the learner grace and a margin: the voter drops its claims node, and
     * presence names that node (bee#28).
     */
    @Test
    fun killedBridgeShowsDepartedOverRealSockets() {
        assumeTrue("real-socket smoke: pass -Pbee.realsocket.tests=true", System.getProperty("bee.realsocket.tests") == "true")
        runBlocking(Dispatchers.Default) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = Files.createTempDirectory("bee-join-smoke-")))
            val http = HttpClient(CIO) { install(WebSockets) }
            val clock = { Clock.System.now() }
            val ada = PeerIdentity.agent("ada", Random.Default)
            try {
                val bridgeJob = Job(scope.coroutineContext[Job])
                val bridgeScope = CoroutineScope(scope.coroutineContext + bridgeJob)
                val author = ReplicaId("mcp-ada")
                val board = BoardClient(
                    author,
                    SpokeLoom(http, Dispatchers.Default, bridgeScope, author.value, Random.Default, clock).pinnedTo(BoardPin.to(host.boardId)),
                    Rendezvous.Existing(WebSocketAdvertisement("ws://127.0.0.1:${host.port}${BoardRelay.PATH}", PeerId("bee-host"), "board")),
                    bridgeScope,
                    clock,
                ).also { it.connect() }
                // The bridge's own connection, as bee-mcp builds it: links under fresh nodes, presence naming each.
                val claims = ClaimsConnection(
                    scope = bridgeScope,
                    nextNode = { NodeId("mcp-ada-${Random.Default.nextLong().toULong()}") },
                    open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, "ws://127.0.0.1:${host.port}", BoardPin.to(host.boardId), linkScope, clock, Random.Default) },
                    onFailure = { },
                    retryDelay = ClaimsConnection.jitteredBackoff(Random.Default),
                )
                val presence = Presence(board, author, claims.link.filterNotNull().map { it.node }, clock, bridgeScope) { throw it }
                withTimeout(10.seconds) { board.online.first { it } }
                val link = withTimeout(10.seconds) { claims.link.filterNotNull().first() }
                presence.update(ada, status = "smoke", lastActedAt = clock(), waiting = false)
                val seen = withTimeoutOrNull(10.seconds) {
                    host.state.first { state -> state.presence.entries.values.any { it.value?.claimsNode == link.node } }
                }
                val target = ClaimTarget("record:smoke:1")
                val won = link.client.claim(target, ada)

                val roster = LivePresence(host.state, clock, scope) { throw it }
                val killedAt = clock()
                val dropped = async {
                    withTimeoutOrNull(45.seconds) {
                        host.voter.membership.first { link.node !in it.allMembers }
                        clock() - killedAt
                    }
                }
                bridgeJob.cancelAndJoin()
                val departed = withTimeoutOrNull(45.seconds) {
                    host.state.first { state -> state.departures().any { it.identity == ada } }
                }
                val droppedAfter = dropped.await()
                val liveAtEnd = roster.live().map { it.identity }
                System.err.println("[join-smoke] dropped after $droppedAfter, departed=${departed != null}, live at end $liveAtEnd")
                assertAll(
                    { assertIs<ClaimOutcome.Won>(won, "rig: the bridge held a claim") },
                    { assertNotNull(seen, "rig: the host saw presence naming the bridge's claims node") },
                    { assertNotNull(droppedAfter, "rig: the voter dropped the killed bridge's claims node") },
                    { assertNotNull(departed, "the board shows the killed bridge departed") },
                    { assertEquals(DepartReason.Crashed, departed?.departures()?.single { it.identity == ada }?.let(Departure::reason)) },
                    { assertEquals("orphaned", departed?.claimView(target)?.status, "its claim shows orphaned") },
                )
            } finally {
                http.close()
                host.close()
                scope.cancel()
            }
        }
    }
}
