package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.identified
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.HoldableConnection
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ClaimsRaceTest {
    @Test
    fun twoClientsRaceAndExactlyOneWins() = runTest(timeout = 300.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val b = rig.client(NodeId("claims-b"))

        val rounds = (1..ROUNDS).map { round ->
            val target = ClaimTarget("record:race:$round")
            val outcomes = listOf(
                async { a.claim(target, alice) },
                async { b.claim(target, bob) },
            ).awaitAll()
            val mirrored = rig.mirrored.first { it.status(target) is ClaimStatus.Held }.status(target) as ClaimStatus.Held
            Triple(target, outcomes, mirrored)
        }

        val wins = rounds.map { (_, outcomes, _) -> outcomes.count { it is ClaimOutcome.Won } }
        val namedWinners = rounds.map { (_, outcomes, mirrored) ->
            val winner = if (outcomes[0] is ClaimOutcome.Won) alice else bob
            mirrored.holder == winner && (outcomes.first { it is ClaimOutcome.Won } as ClaimOutcome.Won).token == mirrored.token
        }
        rig.report(
            "race: $ROUNDS rounds, log committed through ${voter.table.value.appliedIndex}, " +
                "latencies a=${a.latencies.value.summary()} b=${b.latencies.value.summary()}",
        )
        assertAll(
            { assertEquals(List(ROUNDS) { 1 }, wins, "exactly one Won per round") },
            { assertEquals(List(ROUNDS) { true }, namedWinners, "the mirror names the winner and its token") },
        )
        voter.close()
    }

    private companion object {
        const val ROUNDS = 50
    }
}

private fun List<kotlin.time.Duration>.summary(): String =
    if (isEmpty()) "none" else "min ${min()} max ${max()} n $size"

/**
 * The deterministic claims rig shared by the integration tests: one [ClaimsVoter] over an
 * [InMemoryClaimsHost], learners dialling it over in-memory links, all on [TestScope]'s virtual
 * time.
 */
internal class ClaimsRig(private val test: TestScope, val store: DurableStore = InMemoryDurableStore()) {
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    val raftConfig = RaftConfig(expectVirtualTime = true, random = Random(6))
    val host = InMemoryClaimsHost(test.testScheduler, clock)
    val mirrored = MutableStateFlow(ClaimTable.EMPTY)

    suspend fun startVoter(scope: CoroutineScope = test.backgroundScope, host: RoomHost = this.host): ClaimsVoter =
        ClaimsVoter.start(scope, host, store, raftConfig, { mirrored.value = it }, clock)

    fun client(node: NodeId, scope: CoroutineScope = test.backgroundScope): ClaimsClient {
        val cluster = scope.clusterClient(
            loom = host.loomFor(node),
            clusterEndpoints = ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))),
            clientNodeId = node,
            clusterConfig = ClusterConfig(voters = setOf(ClaimsVoter.HOST_NODE), learners = setOf(node)),
            raftConfig = raftConfig,
            clock = clock,
        )
        return ClaimsClient(cluster, clock, scope)
    }

    fun report(line: String) = System.err.println("[claims-spike] $line")
}

/**
 * An in-memory multi-connection [RoomHost], the double of `KtorRoomHost`: every dial through
 * [loomFor] becomes one admitted [Room], so each learner is its own room, as on `/ws/claims`.
 * kuilt's `LoomRoomHost` hosts a single room, and `InMemoryRoomFabric` offers no `RoomHost`.
 *
 * Each dial's server-to-client sends go through a [HoldableConnection], so a test can hold a
 * commit's way back to a learner ([holdFor]).
 */
internal class InMemoryClaimsHost(private val dispatcher: CoroutineContext, private val clock: () -> Instant) : RoomHost {
    private class Link(val server: Connection, val remote: PeerId)

    private val pending = Channel<Link>(Channel.UNLIMITED)
    private val holds = mutableMapOf<NodeId, HoldableConnection>()

    /** The latest link to [node]'s server-to-client direction. */
    fun holdFor(node: NodeId): HoldableConnection = holds.getValue(node)

    fun loomFor(node: NodeId): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val (serverEnd, clientEnd) = connectionPair()
            val held = HoldableConnection(serverEnd)
            holds[node] = held
            pending.send(Link(held, PeerId(node.value)))
            return identified(clientEnd, PeerId(node.value), SERVER, dispatcher)
        }
    }

    override suspend fun start(onRoom: suspend (Room) -> Unit) = coroutineScope {
        val accept = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                val link = pending.receive()
                return identified(link.server, SERVER, link.remote, dispatcher)
            }
        }
        val factory = SeamRoomFactory(loom = accept, scope = this, clock = clock)
        while (true) {
            val room = factory.host(Pattern(SESSION))
            launch { onRoom(room) }
        }
    }

    override fun close() = Unit

    companion object {
        const val SESSION = "claims"
        val SERVER = PeerId("bee-host")
    }
}
