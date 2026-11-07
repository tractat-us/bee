package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
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
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.host.fixtures.FixtureVoter
import us.tractat.bee.host.fixtures.startFixtureVoter
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.ClusterConfig
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

/** The claims path over real loopback WebSockets: Netty, `KtorRoomHost`, CIO clients. */
class ClaimsSmokeTest {
    private val target = ClaimTarget("record:smoke:1")
    private val alice = PeerIdentity.agent("alice", Random(1))

    @Test
    fun claimOverRealSockets() = smoke { voter, scope ->
        val a = client(scope, voter, NodeId("smoke-a"))
        val outcome = a.claim(target, alice)
        System.err.println("[claims-spike] real sockets: claim latency ${a.latencies.value}")
        assertIs<ClaimOutcome.Won>(outcome)
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
        val through = voter.voter.table.value.appliedIndex
        val aSaw = withTimeoutOrNull(10.seconds) { a.table.first { it.appliedIndex >= through } }
        System.err.println(
            "[claims-spike] real sockets, two learners: voter through $through, a through ${a.table.value.appliedIndex}, " +
                "b through ${b.table.value.appliedIndex}, membership ${voter.voter.membership.value}",
        )
        assertAll(
            { assertIs<ClaimOutcome.Won>(first) },
            { assertIs<ClaimOutcome.Won>(second) },
            { assertNotNull(aSaw, "the first learner still folds the log after the second joined") },
        )
    }

    // TEMPORARY diagnostic, removed before the PR is ready: does a client-side close start the grace?
    @Test
    fun diagGoneLearnerOverRealSockets() = smoke { voter, scope ->
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = client(scope, voter, NodeId("smoke-a"))
        a.claim(target, alice)
        val bJob = Job(scope.coroutineContext[Job])
        val b = client(CoroutineScope(scope.coroutineContext + bJob), voter, NodeId("smoke-b"))
        b.claim(ClaimTarget("record:smoke:2"), bob)
        System.err.println("[claims-spike] diag before close: ${voter.voter.membership.value}")
        val closedAt = Clock.System.now()
        bJob.cancelAndJoin()
        val removed = withTimeoutOrNull(45.seconds) { voter.voter.membership.first { NodeId("smoke-b") !in it.learners } }
        System.err.println("[claims-spike] diag after ${Clock.System.now() - closedAt}: removed=$removed now=${voter.voter.membership.value}")
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
        val loom = KtorClientLoom(HttpClient(CIO) { install(WebSockets) }, selfPeerId = PeerId(node.value))
        val cluster = scope.clusterClient(
            loom = loom,
            clusterEndpoints = ClusterEndpoints(listOf(WebSocketAdvertisement(voter.url, FixtureVoter.SERVER, FixtureVoter.SESSION))),
            clientNodeId = node,
            clusterConfig = ClusterConfig(voters = setOf(ClaimsVoter.HOST_NODE), learners = setOf(node)),
            raftConfig = RaftConfig(),
            clock = { Clock.System.now() },
        )
        return ClaimsClient(cluster, { Clock.System.now() }, scope)
    }
}
