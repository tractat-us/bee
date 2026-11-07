package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.DepartReason
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
                "latencies a=${a.latencies.value.summary()} b=${b.latencies.value.summary()}, " +
                "a folded through ${a.table.value.appliedIndex}, b through ${b.table.value.appliedIndex}, " +
                "outcomes ${rounds.map { it.second }}",
        )
        assertAll(
            { assertEquals(List(ROUNDS) { 1 }, wins, "exactly one Won per round") },
            { assertEquals(List(ROUNDS) { true }, namedWinners, "the mirror names the winner and its token") },
        )
        voter.close()
    }

    /**
     * Fit check, spike 4 Step 1: a learner keeps receiving the committed log after a second
     * learner is admitted. The voter's `Depart` after both joined must reach both tables.
     */
    @Test
    fun aSecondLearnerLeavesTheFirstOnTheLog() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val first = a.claim(ClaimTarget("record:fit:1"), alice)
        val b = rig.client(NodeId("claims-b"))
        val second = b.claim(ClaimTarget("record:fit:2"), bob)
        voter.depart(bob, DepartReason.Crashed)
        // The Depart's own index: `depart` returns before the voter's fold reaches it.
        val through = voter.table.first { it.retired(bob) }.appliedIndex
        val aSaw = withTimeoutOrNull(10.seconds) { a.table.first { it.appliedIndex >= through } }
        val bSaw = withTimeoutOrNull(10.seconds) { b.table.first { it.appliedIndex >= through } }
        rig.report(
            "two learners: voter through $through, a folded through ${a.table.value.appliedIndex}, " +
                "b through ${b.table.value.appliedIndex}; outcomes $first, $second; " +
                "voter membership ${voter.membership.value}",
        )
        assertAll(
            {
                assertEquals(
                    setOf(NodeId("claims-a"), NodeId("claims-b")),
                    voter.membership.value.learners,
                    "both learners are in the voter's configuration",
                )
            },
            { assertIs<ClaimOutcome.Won>(first, "a's claim, before b joined") },
            { assertIs<ClaimOutcome.Won>(second, "b's claim") },
            { assertNotNull(bSaw, "the newest learner folds the log") },
            { assertNotNull(aSaw, "the first learner still folds the log after the second joined") },
        )
        voter.close()
    }

    /**
     * A learner that goes away leaves the voter's config once it has been gone for
     * [ClaimsVoter.LEARNER_GRACE], and not before. The learner that stays keeps claiming.
     *
     * The removal's virtual time is the signal: the overload without a grace never removes (null),
     * and a shorter grace removes early (elapsed under the grace).
     */
    @Test
    fun aLearnerGoneForTheGraceLeavesTheConfig() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val bJob = Job(backgroundScope.coroutineContext[Job])
        val bScope = CoroutineScope(backgroundScope.coroutineContext + bJob)
        val b = rig.client(NodeId("claims-b"), bScope)
        val joined = listOf(a.claim(ClaimTarget("record:grace:1"), alice), b.claim(ClaimTarget("record:grace:2"), bob))
        val bothIn = voter.membership.value.learners

        val gone = testScheduler.currentTime
        bJob.cancelAndJoin()
        val removed = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) {
            voter.membership.first { NodeId("claims-b") !in it.learners }
        }
        val elapsed = (testScheduler.currentTime - gone).milliseconds
        val after = a.claim(ClaimTarget("record:grace:3"), alice)
        rig.report("grace: b removed after $elapsed, membership ${voter.membership.value}")
        assertAll(
            { assertEquals(List(2) { true }, joined.map { it is ClaimOutcome.Won }, "rig: both learners were admitted") },
            { assertEquals(setOf(NodeId("claims-a"), NodeId("claims-b")), bothIn, "rig: both learners were in the config") },
            { assertEquals(setOf(NodeId("claims-a")), removed?.learners, "the gone learner leaves; the other stays") },
            { assertTrue(elapsed >= ClaimsVoter.LEARNER_GRACE, "not removed before the grace, was $elapsed") },
            { assertIs<ClaimOutcome.Won>(after, "the remaining learner still claims") },
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
