package us.tractat.bee.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.ForwardedProposalFailedException
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One claims connection: a `clusterClient` learner and the [ClaimsClient] over it, under a
 * single Raft [node]. Build it with [open], which derives the loom's peer id from that same
 * [node], so the id the voter replicates to is always the one the client answers to.
 */
class ClaimsLink private constructor(val node: NodeId, private val cluster: ClusterClient, val client: ClaimsClient) {
    /**
     * Proposes `Depart(identity, reason)` through Raft. v1 trusts a learner's `Depart` (spike 4
     * notes). True if it committed within [timeout].
     */
    suspend fun depart(identity: PeerIdentity, reason: DepartReason, timeout: Duration = 10.seconds): Boolean =
        try {
            withTimeoutOrNull(timeout) { cluster.propose(ClaimCommand.encode(ClaimCommand.Depart(identity, reason))) } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LeadershipLostException) {
            false
        } catch (_: ForwardedProposalFailedException) {
            false
        } catch (_: NotLeaderException) {
            false
        }

    companion object {
        /** The host voter's Raft node id: must equal `ClaimsVoter.HOST_NODE`. */
        val VOTER: NodeId = NodeId("bee-host")

        /** The claims room's route, session name and server peer at the host: must equal `BeeHost`'s. */
        const val PATH: String = "/ws/claims"
        const val SESSION: String = "claims"
        val SERVER: PeerId = PeerId("bee-host")

        /**
         * A claims link under [node], dialling through the loom [loomFor] builds for the peer id
         * `PeerId(node.value)`. The loom and the learner can't disagree on who this client is.
         *
         * @param random draws the [ClaimsClient]'s incarnation; never seed it from [node].
         */
        @Suppress("LongParameterList")
        fun open(
            node: NodeId,
            loomFor: (PeerId) -> Loom,
            endpoints: ClusterEndpoints,
            scope: CoroutineScope,
            raftConfig: RaftConfig,
            clock: () -> Instant,
            random: Random,
        ): ClaimsLink {
            val cluster = scope.clusterClient(
                loom = loomFor(PeerId(node.value)),
                clusterEndpoints = endpoints,
                clientNodeId = node,
                clusterConfig = ClusterConfig(voters = setOf(VOTER), learners = setOf(node)),
                raftConfig = raftConfig,
                clock = clock,
            )
            return ClaimsLink(node, cluster, ClaimsClient(cluster, node, clock, scope, random))
        }

        /**
         * [open] over a real WebSocket to the host's [PATH] at [host], with its key if it has one,
         * naming [board]'s board: the host refuses a link to any other (bee#46).
         */
        @Suppress("LongParameterList")
        fun overWebSocket(
            node: NodeId,
            http: HttpClient,
            host: HostAddress,
            board: BoardPin,
            scope: CoroutineScope,
            clock: () -> Instant,
            random: Random,
        ): ClaimsLink = open(
            node = node,
            loomFor = { peer -> KtorClientLoom(http, selfPeerId = peer).pinnedTo(board) },
            endpoints = ClusterEndpoints(listOf(WebSocketAdvertisement(host.socket(PATH), SERVER, SESSION))),
            scope = scope,
            raftConfig = RaftConfig(),
            clock = clock,
            random = random,
        )
    }
}

/**
 * A [ClaimsLink] kept alive. Invariant: **[link] is a link that has proved itself (it folded a
 * committed entry), or null; and a `Depart` or a release that couldn't commit is proposed again
 * until it does.**
 *
 * TODO(kuilt#3110): a refused dial ends `clusterClient`'s reconnect loop with an uncaught
 * exception, which kills the link for good. Each link here runs in its own job; when that job
 * fails, the link is dropped, [onFailure] hears why, and a fresh one is opened, under a fresh
 * node, after [retryDelay] for that attempt. The attempt count resets only once a link has proved
 * itself, since `clusterClient` dials after it returns. Remove the rebuild once kuilt#3110 lands.
 *
 * @param open builds a link under the given node on the given scope; [ClaimsLink.open] or
 *   [ClaimsLink.overWebSocket].
 * @param nextNode mints a fresh node id for each link: kuilt keeps a node id unique across the cluster.
 * @param retryDelay the wait before the given attempt; [jitteredBackoff] in production. It also
 *   paces the retry of pending proposals on a live link.
 */
@Suppress("LongParameterList")
class ClaimsConnection(
    private val scope: CoroutineScope,
    private val nextNode: () -> NodeId,
    private val open: (NodeId, CoroutineScope) -> ClaimsLink,
    private val onFailure: (Throwable) -> Unit,
    private val retryDelay: (Int) -> Duration,
) {
    /** A release of a claim nobody received: [holder] won [target] under [token]. */
    data class Unreceived(val target: ClaimTarget, val holder: PeerIdentity, val token: ClaimToken)

    private val current = MutableStateFlow<ClaimsLink?>(null)
    private val pending = MutableStateFlow<List<Pair<PeerIdentity, DepartReason>>>(emptyList())
    private val releases = MutableStateFlow<List<Unreceived>>(emptyList())
    private val attempts = MutableStateFlow(0)

    /** The live, proved link, or null while the claims connection is down or catching up. */
    val link: StateFlow<ClaimsLink?> = current.asStateFlow()

    /** `Depart`s waiting to commit. */
    val pendingDeparts: StateFlow<List<Pair<PeerIdentity, DepartReason>>> = pending.asStateFlow()

    /** Releases of claims nobody received, waiting to commit. */
    val pendingReleases: StateFlow<List<Unreceived>> = releases.asStateFlow()

    /** How many links this connection has opened. */
    val opened: StateFlow<Int> = attempts.asStateFlow()

    private val supervisor: Job = scope.launch(CoroutineName("claims-connection")) {
        var attempt = 0
        while (true) {
            val failed = MutableStateFlow<Throwable?>(null)
            // A root job: a failure inside the link ends the link, never this supervisor. The
            // handler hears the failure, since a root job hands it on rather than handling it.
            val job = Job()
            val linkScope = CoroutineScope(
                scope.coroutineContext + job + CoroutineExceptionHandler { _, e -> failed.value = e },
            )
            try {
                attempts.update { it + 1 }
                val opened = try {
                    open(nextNode(), linkScope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    failed.value = e
                    null
                }
                if (opened != null) {
                    linkScope.launch { prove(opened) { attempt = 0 } }
                    job.join()
                }
            } finally {
                current.value = null
                job.cancel()
            }
            failed.value?.let(onFailure)
            delay(retryDelay(attempt++))
        }
    }

    /** Publishes [live] once it has folded a committed entry, then keeps its pending proposals flowing. */
    private suspend fun prove(live: ClaimsLink, proved: () -> Unit) {
        live.client.table.first { it.appliedIndex > 0 }
        proved()
        current.value = live
        var retry = 0
        while (true) {
            combine(pending, releases) { d, r -> d.isNotEmpty() || r.isNotEmpty() }.first { it }
            pending.value.forEach { d -> if (live.depart(d.first, d.second)) pending.update { it - d } }
            releases.value.forEach { r -> if (release(live, r)) releases.update { it - r } }
            if (pending.value.isEmpty() && releases.value.isEmpty()) retry = 0 else delay(retryDelay(retry++))
        }
    }

    /**
     * True once [r] is settled: released now, or no longer held under its token by a table that
     * has folded the claim's own entry, so a link still catching up never reads it as settled.
     */
    private suspend fun release(live: ClaimsLink, r: Unreceived): Boolean =
        live.client.release(r.target, r.holder, r.token) ||
            live.client.table.value.let { it.appliedIndex >= r.token.index && it.status(r.target) != ClaimStatus.Held(r.holder, r.token) }

    /**
     * Proposes `Depart(identity, reason)` on the live link. If there is none, or it doesn't
     * commit, it's kept and proposed again until it does. True if it committed now.
     */
    suspend fun depart(identity: PeerIdentity, reason: DepartReason): Boolean {
        val live = current.value
        if (live != null && live.depart(identity, reason)) return true
        pending.update { it + (identity to reason) }
        return false
    }

    /**
     * Releases a claim nobody received, now if a link is up, else once one is. Asking again for
     * one already queued tries it now and keeps one copy. A caller cancelled mid-try leaves it
     * queued, so it is never dropped. True if it settled now.
     */
    suspend fun releaseUnreceived(unreceived: Unreceived): Boolean {
        val live = current.value
        val settled = try {
            live != null && release(live, unreceived)
        } catch (cancelled: CancellationException) {
            queue(unreceived)
            throw cancelled
        }
        if (settled) releases.update { it - unreceived } else queue(unreceived)
        return settled
    }

    /**
     * Queues a release of a claim nobody received, for the live link to propose until it commits;
     * one already queued keeps one copy. It never suspends, so nothing between a caller's decision
     * and the queue can fail.
     */
    fun queue(unreceived: Unreceived) = releases.update { if (unreceived in it) it else it + unreceived }

    /** Stops rebuilding, and stops the live link. */
    fun close() {
        supervisor.cancel()
    }

    companion object {
        /** Full-jitter exponential backoff, 250 ms to 5 s, as the board link redials. */
        fun jitteredBackoff(random: Random): (Int) -> Duration {
            val backoff = ExponentialBackoff(base = 250.milliseconds, cap = 5.seconds, random = random)
            return { attempt -> backoff.delay(attempt) }
        }
    }
}
