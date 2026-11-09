package us.tractat.bee.core

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.crdt.EphemeralMapTracker
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One peer's presence on the board (§6.4). Invariant: **while it runs, the peer's entry is
 * re-posted every [EVERY], so a live peer never ages out of [LivePresence] and a gone one does
 * within [LivePresence.TTL].**
 *
 * The entry is whatever [update] last set, posted under [author] with the node of the peer's
 * newest claims link ([PresenceEntry.claimsNode]) and whether any of its [waiting] blocks is in
 * progress. Each post carries a clock that only grows, so the newest post wins on every replica.
 * Both are read as the post is made, under the lock that hands out its clock, so the newest post
 * always carries the newest of each: no writer can post a stale `waiting` (bee#165).
 *
 * The node is the newest one [claimsNodes] gave, and it stays named while that link is down: the
 * host departs this peer `Crashed` once the voter drops the node it names (bee#28). A rebuilt link
 * is posted at once, so the old node's removal finds nobody naming it. A peer whose claims link
 * stays down for the learner grace is departed, the cost bee#28's ruling accepts: it can't claim
 * anything in that state. Naming nothing while down would instead leave a peer that crashed then
 * departed by nothing.
 *
 * @param author the [client]'s CRDT author, which the entry is posted under.
 * @param claimsNodes the node of each claims link as it comes up: for a bridge, the nodes of its
 *   `ClaimsConnection.link`.
 * @param onFailure hears a failure of the roster's tracker, which the roster survives.
 */
class Presence(
    private val client: BoardClient,
    private val author: ReplicaId,
    claimsNodes: Flow<NodeId>,
    private val clock: () -> Instant,
    scope: CoroutineScope,
    onFailure: (Throwable) -> Unit,
) {
    private val mutex = Mutex()
    private val facts = MutableStateFlow<Facts?>(null)
    private val claimsNode = MutableStateFlow<NodeId?>(null)
    private val waits = MutableStateFlow(0)
    private var lastClock = 0L

    /** Everyone attached, as this peer's replica sees them. */
    val roster: LivePresence = LivePresence(client.state, clock, scope, onFailure)

    init {
        scope.launch(CoroutineName("presence-${author.value}")) {
            while (true) {
                post()
                delay(EVERY)
            }
        }
        // A fresh node is posted at once: the old node's removal must find the new one named.
        scope.launch(CoroutineName("presence-${author.value}-claims")) {
            claimsNodes.collect { node ->
                claimsNode.value = node
                post()
            }
        }
    }

    /** Sets what this peer shows, and posts it at once. */
    suspend fun update(identity: PeerIdentity, status: String?, lastActedAt: Instant) {
        facts.value = Facts(identity, status, lastActedAt)
        post()
    }

    /**
     * Runs [block] with this peer shown waiting: it [update]s at the start, and posts again at the
     * end, which counts as acting, since waiting is never quiet. Blocks may overlap, as an HTTP
     * session's calls do: the peer shows waiting while any is in progress, through every post
     * meanwhile. The count is taken before anything can suspend and given back however the block
     * ends, so a cancelled wait never leaves the peer shown waiting.
     */
    suspend fun <T> waiting(identity: PeerIdentity, status: String?, lastActedAt: Instant, block: suspend () -> T): T {
        waits.update { it + 1 }
        try {
            update(identity, status, lastActedAt)
            return block()
        } finally {
            waits.update { it - 1 }
            touch(clock())
            withContext(NonCancellable) { post() }
        }
    }

    /**
     * Records that this peer acted at [at], without posting: the next post, within [EVERY],
     * carries it. For a caller that can't suspend, such as a read-only tool.
     */
    fun touch(at: Instant) {
        facts.update { it?.copy(lastActedAt = maxOf(it.lastActedAt, at)) }
    }

    private suspend fun post() {
        val (entry, at) = mutex.withLock {
            val current = facts.value ?: return
            val now = clock().toEpochMilliseconds()
            lastClock = maxOf(lastClock + 1, now)
            current.entry(claimsNode.value, waiting = waits.value > 0) to lastClock
        }
        client.mutate { it.postPresence(author, entry, at) }
    }

    private data class Facts(val identity: PeerIdentity, val status: String?, val lastActedAt: Instant) {
        fun entry(claimsNode: NodeId?, waiting: Boolean) = PresenceEntry(identity, claimsNode, status, lastActedAt, waiting)
    }

    companion object {
        /** How often a peer re-posts its entry: well inside [LivePresence.TTL]. */
        val EVERY: Duration = 5.seconds
    }
}

/**
 * The attached peers on [board], as one replica sees them: each entry whose last change is
 * younger than [TTL] on this replica's [clock] (kuilt's [EphemeralMapTracker]).
 *
 * The board carries merged presence, not each author's own post, so every change is fed to the
 * tracker as relayed: it stamps only entries that advance and never evicts on a re-delivery.
 *
 * @param onFailure hears a failed tracker update; the next board change is still fed.
 */
class LivePresence(
    board: StateFlow<BoardState>,
    private val clock: () -> Instant,
    scope: CoroutineScope,
    onFailure: (Throwable) -> Unit,
) {
    private val mutex = Mutex()
    private val tracker = EphemeralMapTracker<PresenceEntry>(TTL.inWholeMilliseconds) { clock().toEpochMilliseconds() }

    init {
        board.pumpIn(
            scope,
            onFailure = { _, failure ->
                onFailure(failure)
                PumpVerdict.CONTINUE
            },
            name = "live-presence",
        ) { state -> mutex.withLock { tracker.relayed(state.presence) } }
    }

    /** The live entries, one per posting replica. */
    suspend fun live(): List<PresenceEntry> = mutex.withLock { tracker.live().values.toList() }

    /**
     * The attached peers, one entry per identity: its newest by [PresenceEntry.lastActedAt]. A
     * person with two tabs open posts from two replicas, and a reloaded tab leaves its old entry
     * live for up to [TTL]; each shows here once.
     */
    suspend fun peers(): Map<PeerIdentity, PresenceEntry> =
        live().groupBy { it.identity }.mapValues { (_, entries) -> entries.maxBy { it.lastActedAt } }

    companion object {
        /** How long an entry stays live without a fresh post: the spec's 30 s reconnect window. */
        val TTL: Duration = 30.seconds
    }
}
