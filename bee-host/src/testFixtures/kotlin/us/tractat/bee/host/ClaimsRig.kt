package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.ClaimsLink
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.identified
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.fabric.HoldableConnection
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The deterministic claims rig shared by the integration tests: one [ClaimsVoter] over an
 * [InMemoryClaimsHost], learners dialling it over in-memory links, all on [TestScope]'s virtual
 * time.
 */
class ClaimsRig(private val test: TestScope, val store: DurableStore = InMemoryDurableStore()) {
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    val raftConfig = RaftConfig(expectVirtualTime = true, random = Random(6))
    // A test dispatcher on the test's scheduler, not the scheduler itself: `identified` runs the seam
    // loops on what it is given, and a bare scheduler has no dispatcher, so they ran on Default in real time.
    val host = InMemoryClaimsHost(StandardTestDispatcher(test.testScheduler), clock)
    val mirrored = MutableStateFlow(ClaimTable.EMPTY)
    private var clientSeeds = 0

    suspend fun startVoter(scope: CoroutineScope = test.backgroundScope, host: RoomHost = this.host): ClaimsVoter =
        ClaimsVoter.start(scope, host, store, raftConfig, { mirrored.value = it }, clock)

    /** A learner under [node], built by the bridge's own factory, so its loom is named after [node]. */
    fun client(node: NodeId, scope: CoroutineScope = test.backgroundScope): ClaimsClient =
        ClaimsLink.open(
            node = node,
            loomFor = { peer -> host.loomFor(NodeId(peer.value)) },
            endpoints = ClusterEndpoints(listOf(InMemoryTag(InMemoryClaimsHost.SESSION))),
            scope = scope,
            raftConfig = raftConfig,
            clock = clock,
            // One seed per client in this rig, never from the node: a reused node must draw a new incarnation.
            random = Random(++clientSeeds),
        ).client

    fun report(line: String) = System.err.println("[claims-spike] $line")
}

/**
 * An in-memory multi-connection [RoomHost], the double of `KtorRoomHost`: every dial through
 * [loomFor] becomes one admitted [Room], so each learner is its own room, as on `/ws/claims`.
 * kuilt's `LoomRoomHost` hosts a single room, and `InMemoryRoomFabric` offers no `RoomHost`.
 *
 * Each dial's sends go through a [HoldableConnection] in each direction, so a test can hold a
 * commit's way back to a learner ([holdFor]) or a learner's proposal on its way up ([holdUpFor]).
 * A learner's sends can also be lost on their way up ([dropUpFor]).
 */
class InMemoryClaimsHost(private val dispatcher: CoroutineContext, private val clock: () -> Instant) : RoomHost {
    private class Link(val server: Connection, val remote: PeerId)

    private val pending = Channel<Link>(Channel.UNLIMITED)
    private val holds = mutableMapOf<NodeId, HoldableConnection>()
    private val upHolds = mutableMapOf<NodeId, HoldableConnection>()
    private val upDrops = mutableMapOf<NodeId, DroppableConnection>()

    /** The latest link to [node]'s server-to-client direction. */
    fun holdFor(node: NodeId): HoldableConnection = holds.getValue(node)

    /** The latest link to [node]'s client-to-server direction: its proposals and acks. */
    fun holdUpFor(node: NodeId): HoldableConnection = upHolds.getValue(node)

    /** The latest link to [node]'s client-to-server direction, where its sends can be lost. */
    fun dropUpFor(node: NodeId): DroppableConnection = upDrops.getValue(node)

    fun loomFor(node: NodeId): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val (serverEnd, clientEnd) = connectionPair()
            val held = HoldableConnection(serverEnd)
            holds[node] = held
            pending.send(Link(held, PeerId(node.value)))
            val lossy = DroppableConnection(clientEnd)
            upDrops[node] = lossy
            val up = HoldableConnection(lossy)
            upHolds[node] = up
            return identified(up, PeerId(node.value), SERVER, dispatcher)
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

/**
 * A [Connection] that can lose what it sends: while [dropping], a send returns as if it went, and
 * the frame is gone. Unlike a held send, a dropped one never arrives, as on a link that died with
 * the frame on it.
 */
class DroppableConnection(private val delegate: Connection) : Connection by delegate {
    @Volatile var dropping: Boolean = false

    private val lost = MutableStateFlow(0)

    /** How many sends were lost, so a test can assert its drop caught something. */
    val dropped: Int get() = lost.value

    override suspend fun send(frame: ByteArray) {
        if (dropping) lost.update { it + 1 } else delegate.send(frame)
    }
}
