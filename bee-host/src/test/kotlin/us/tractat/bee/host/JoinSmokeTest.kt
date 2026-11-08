package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.Departure
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
import kotlin.test.Ignore
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
     * orphaned, within the reconnect window and a margin.
     */
    @Ignore("needs #28: nothing feeds Departures a lost board link yet")
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
                    SpokeLoom(http, Dispatchers.Default, bridgeScope, author.value, Random.Default, clock),
                    Rendezvous.Existing(WebSocketAdvertisement("ws://127.0.0.1:${host.port}${BoardRelay.PATH}", PeerId("bee-host"), "board")),
                    bridgeScope,
                    clock,
                ).also { it.connect() }
                // The bridge's own factory: the loom and the learner built from one node.
                val claims = ClaimsLink.overWebSocket(NodeId("mcp-ada-1"), http, "ws://127.0.0.1:${host.port}", bridgeScope, clock, Random.Default).client
                val presence = Presence(board, author, clock, bridgeScope) { throw it }
                withTimeout(10.seconds) { board.online.first { it } }
                presence.update(ada, status = "smoke", lastActedAt = clock(), waiting = false)
                val target = ClaimTarget("record:smoke:1")
                val won = claims.claim(target, ada)

                bridgeJob.cancelAndJoin()
                val departed = withTimeoutOrNull(45.seconds) {
                    host.state.first { state -> state.departures().any { it.identity == ada } }
                }
                assertAll(
                    { assertIs<ClaimOutcome.Won>(won, "rig: the bridge held a claim") },
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
