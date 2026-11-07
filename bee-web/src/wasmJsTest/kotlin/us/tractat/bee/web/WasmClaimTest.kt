package us.tractat.bee.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The fixture voter's port, which the build injects with `-Pbee.realsocket.tests=true`; -1 without it. */
private fun fixtureVoterPort(): Int =
    js("(typeof window.__BEE_FIXTURE_VOTER_PORT__ === 'number') ? window.__BEE_FIXTURE_VOTER_PORT__ : -1")

/**
 * Fit check (a), spike 4 Step 1: `clusterClient` over `KtorClientLoom` on the browser's own
 * WebSocket, in wasm, claims through the fixture voter. Skips without the fixture voter.
 */
class WasmClaimTest {
    @Test
    fun browserClaimsThroughTheVoter() = runTest(timeout = 60.seconds) {
        val port = fixtureVoterPort()
        if (port < 0) {
            println("WasmClaimTest skipped: no fixture voter (pass -Pbee.realsocket.tests=true)")
            return@runTest
        }
        // Real time: the claim crosses a real socket, so the test's virtual clock can't drive it.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                // A Raft node id per connection; the claimant is the person, across tabs.
                val node = NodeId("web-" + Random(Clock.System.now().toEpochMilliseconds()).nextLong().toULong().toString(16))
                val by = PeerIdentity.human("wasm-test")
                val loom = KtorClientLoom(HttpClient(Js) { install(WebSockets) }, selfPeerId = PeerId(node.value))
                val cluster = scope.clusterClient(
                    loom = loom,
                    clusterEndpoints = ClusterEndpoints(
                        listOf(WebSocketAdvertisement("ws://127.0.0.1:$port/ws/claims", PeerId("bee-host"), "claims")),
                    ),
                    clientNodeId = node,
                    clusterConfig = ClusterConfig(voters = setOf(NodeId("bee-host")), learners = setOf(node)),
                    raftConfig = RaftConfig(),
                    clock = { Clock.System.now() },
                )
                val claims = ClaimsClient(cluster, { Clock.System.now() }, scope)
                val outcome = withTimeout(30.seconds) { claims.claim(ClaimTarget("record:wasm:${node.value}"), by) }
                println("[claims-spike] wasm: claim latency ${claims.latencies.value}")
                assertIs<ClaimOutcome.Won>(outcome)
            } finally {
                scope.cancel()
            }
        }
    }
}
