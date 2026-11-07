package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.cluster.ServerCluster
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.FileChannelDurableStore
import java.io.File
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.ClaimsVoter")

/** Where the host publishes each committed claim table: the board's claims mirror (Task 7). */
fun interface ClaimMirror {
    suspend fun publish(table: ClaimTable)
}

/**
 * The host's single Raft voter for claims, over durable storage. In v1 it is the whole voter set,
 * so it is a sequencer, not consensus: a claim commits once this voter appends it.
 *
 * Invariant: the voter never claims. Its only proposal is [depart]; every claim comes from a
 * learner. It folds the committed log from index 1 on every boot and publishes each table to the
 * [ClaimMirror], so the mirror is rebuilt after a restart.
 */
class ClaimsVoter private constructor(
    private val cluster: ServerCluster,
    private val node: RaftNode,
    private val tableState: MutableStateFlow<ClaimTable>,
    private val relay: Job,
    private val job: Job,
) {
    val table: StateFlow<ClaimTable> = tableState.asStateFlow()

    /** Retires [identity] through the log: its claims are orphaned and its later commands rejected. */
    suspend fun depart(identity: PeerIdentity, reason: DepartReason) {
        node.propose(ClaimCommand.encode(ClaimCommand.Depart(identity, reason)))
    }

    /** Stops the voter and its relay. The store stays as it is, for the next boot. */
    suspend fun close() {
        // The relay first: its learners' teardown evicts them from the cluster's overlay, which
        // throws once `cluster.close()` has closed the overlay's Quilter.
        relay.cancelAndJoin()
        cluster.close()
        job.cancel()
    }

    companion object {
        /** The voter's node id. Learners name it as the cluster's one voter. */
        val HOST_NODE: NodeId = NodeId("bee-host")

        /** Starts the voter over a [FileChannelDurableStore] in [storeDir]. */
        suspend fun start(
            scope: CoroutineScope,
            host: RoomHost,
            storeDir: File,
            raftConfig: RaftConfig,
            mirror: ClaimMirror,
            clock: () -> Instant,
        ): ClaimsVoter = start(scope, host, FileChannelDurableStore(storeDir.apply { mkdirs() }), raftConfig, mirror, clock)

        /** Starts the voter over any [store]; tests pass an `InMemoryDurableStore`. */
        suspend fun start(
            scope: CoroutineScope,
            host: RoomHost,
            store: DurableStore,
            raftConfig: RaftConfig,
            mirror: ClaimMirror,
            clock: () -> Instant,
        ): ClaimsVoter {
            val booted = clock()
            val storage = DurableStoreRaftStorage.open(store)
            val job = Job(scope.coroutineContext[Job])
            val voterScope = CoroutineScope(scope.coroutineContext + job)
            val cluster = voterScope.serverCluster(host, voterIds = listOf(HOST_NODE), raftConfig, storageFactory = { storage })
            val node = cluster.voterNodes.getValue(HOST_NODE)
            val tableState = MutableStateFlow(ClaimTable.EMPTY)
            // committedFrom(1) replays the durable log, then tails: the re-fold on boot.
            voterScope.launch(start = CoroutineStart.UNDISPATCHED) {
                node.committedFrom(1).collect { committed ->
                    val next = tableState.value.fold(committed)
                    tableState.value = next
                    mirror.publish(next)
                }
            }
            cluster.awaitLeader()
            val relay = voterScope.launch { cluster.start() }
            log.info { "claims voter up after ${clock() - booted}, log committed through ${node.commitIndex.value}" }
            return ClaimsVoter(cluster, node, tableState, relay, job)
        }
    }
}
