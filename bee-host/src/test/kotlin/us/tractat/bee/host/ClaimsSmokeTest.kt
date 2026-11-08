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
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.host.fixtures.FixtureVoter
import us.tractat.bee.host.fixtures.startFixtureVoter
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The claims path over real loopback WebSockets: Netty, `KtorRoomHost`, CIO clients. */
class ClaimsSmokeTest {
    private val target = ClaimTarget("record:smoke:1")
    private val alice = PeerIdentity.agent("alice", Random(1))

    @Test
    fun claimOverRealSockets() = smoke { voter, scope ->
        val a = client(scope, voter, NodeId("smoke-a"))
        val started = TimeSource.Monotonic.markNow()
        val outcome = a.claim(target, alice)
        val took = started.elapsedNow()
        System.err.println("[claims-spike] real sockets: claim latency ${a.latencies.value}")
        assertIs<ClaimOutcome.Won>(
            outcome,
            "a claim over real sockets came to $outcome after $took; voter table through ${voter.voter.table.value.appliedIndex}, " +
                "client table through ${a.table.value.appliedIndex}, membership ${voter.voter.membership.value}",
        )
    }

    /**
     * The two-learner fit check on the production stack. Red against kuilt `0.7.0-dev.1690`:
     * see `docs/spikes/2026-11-spike-4-claims.md`.
     */
    @Test
    fun aSecondLearnerLeavesTheFirstOnTheLogOverRealSockets() = smoke { voter, scope ->
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = client(scope, voter, NodeId("smoke-a"))
        val first = a.claim(target, alice)
        val b = client(scope, voter, NodeId("smoke-b"))
        val second = b.claim(ClaimTarget("record:smoke:2"), bob)
        voter.voter.depart(bob, DepartReason.Crashed)
        val through = voter.voter.table.first { it.retired(bob) }.appliedIndex
        val aSaw = withTimeoutOrNull(10.seconds) { a.table.first { it.appliedIndex >= through } }
        System.err.println(
            "[claims-spike] real sockets, two learners: voter through $through, a through ${a.table.value.appliedIndex}, " +
                "b through ${b.table.value.appliedIndex}, membership ${voter.voter.membership.value}",
        )
        assertAll(
            { assertIs<ClaimOutcome.Won>(first, "the first learner's claim came to $first") },
            { assertIs<ClaimOutcome.Won>(second, "the second learner's claim came to $second") },
            { assertNotNull(aSaw, "the first learner still folds the log after the second joined") },
        )
    }

    private fun smoke(body: suspend (FixtureVoter, CoroutineScope) -> Unit) {
        assumeTrue("real-socket smoke: pass -Pbee.realsocket.tests=true", System.getProperty("bee.realsocket.tests") == "true")
        runBlocking(Dispatchers.Default) {
            val voterScope = CoroutineScope(coroutineContext + SupervisorJob())
            val clientScope = CoroutineScope(coroutineContext + SupervisorJob())
            val voter = startFixtureVoter(voterScope, Files.createTempDirectory("bee-claims-smoke").toFile())
            try {
                withTimeout(60.seconds) { body(voter, clientScope) }
            } finally {
                // Clients first: a client's reconnect loop that dials a stopped voter ends with an
                // uncaught ConnectException, which the next runTest in this JVM reports as its own.
                clientScope.coroutineContext[Job]!!.cancelAndJoin()
                voter.stop()
                voterScope.cancel()
            }
        }
    }

    private fun client(scope: CoroutineScope, voter: FixtureVoter, node: NodeId): ClaimsClient {
        val http = HttpClient(CIO) { install(WebSockets) }
        return ClaimsLink.open(
            node = node,
            loomFor = { peer -> KtorClientLoom(http, selfPeerId = peer) },
            endpoints = ClusterEndpoints(listOf(WebSocketAdvertisement(voter.url, FixtureVoter.SERVER, FixtureVoter.SESSION))),
            scope = scope,
            raftConfig = RaftConfig(),
            clock = { Clock.System.now() },
            random = Random.Default,
        ).client
    }
}
