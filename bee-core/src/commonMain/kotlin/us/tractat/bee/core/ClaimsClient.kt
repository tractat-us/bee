package us.tractat.bee.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a claim came to. */
sealed interface ClaimOutcome {
    /** This claim committed first: hold [token] and pass it to `release` or `complete`. */
    data class Won(val token: ClaimToken) : ClaimOutcome

    /**
     * Another claim got there first, and [holder] holds the target, or held it to done. Null when
     * the target was free but the claimant is retired.
     */
    data class Lost(val holder: PeerIdentity?) : ClaimOutcome

    /**
     * No commit within the timeout, so the outcome is unknown. Never read as a win. If the claim
     * commits later and wins, the client releases it.
     */
    data object Unavailable : ClaimOutcome
}

/**
 * A peer's way to claim through the voter: proposes [ClaimCommand]s on a [ClusterClient] learner
 * and folds the committed log into [table].
 *
 * Invariant: a claim this client reported as anything but [ClaimOutcome.Won] is never left held by
 * this client. A late commit of a claim it gave up on is released with the token it won.
 *
 * It collects [cluster]'s committed stream, so it must be the stream's only collector, and is
 * built before the client's first join (the stream has no replay).
 */
class ClaimsClient(
    private val cluster: ClusterClient,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
) {
    private val tableState = MutableStateFlow(ClaimTable.EMPTY)
    val table: StateFlow<ClaimTable> = tableState.asStateFlow()

    /** The target's status right after each recent entry, so a proposer reads its own verdict exactly. */
    private val verdicts = MutableStateFlow<Map<Long, ClaimStatus>>(emptyMap())

    /** Claims reported [ClaimOutcome.Unavailable]: a later win of one of these is released. */
    private val abandoned = MutableStateFlow<Set<Pair<ClaimTarget, PeerIdentity>>>(emptySet())

    /** Claim latencies, in proposal order, for the spike's report. */
    private val latencyLog = MutableStateFlow<List<Duration>>(emptyList())
    val latencies: StateFlow<List<Duration>> = latencyLog.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cluster.committed.collect(::fold)
        }
    }

    suspend fun claim(target: ClaimTarget, by: PeerIdentity, timeout: Duration = 10.seconds): ClaimOutcome {
        abandoned.update { it - (target to by) }
        val started = clock()
        val status = propose(ClaimCommand.Claim(target, by), timeout)
        if (status == null) {
            abandoned.update { it + (target to by) }
            releaseIfWon(target, by, table.value.status(target), ownTokenUnknown = true)
            return ClaimOutcome.Unavailable
        }
        latencyLog.update { (it + (clock() - started)).takeLast(VERDICTS_KEPT) }
        val (entry, after) = status
        return when {
            after is ClaimStatus.Held && after.holder == by && after.token == entry.token() -> ClaimOutcome.Won(after.token)
            after is ClaimStatus.Held -> ClaimOutcome.Lost(after.holder)
            after is ClaimStatus.Orphaned -> ClaimOutcome.Lost(after.holder)
            after is ClaimStatus.Done -> ClaimOutcome.Lost(after.by)
            else -> ClaimOutcome.Lost(null)
        }
    }

    /** True if the release committed and freed [target]; false if it was rejected or unavailable. */
    suspend fun release(target: ClaimTarget, by: PeerIdentity, token: ClaimToken?, timeout: Duration = 10.seconds): Boolean =
        propose(ClaimCommand.Release(target, by, token), timeout)?.second == ClaimStatus.Free

    /** The new token if the reassign committed, else null. */
    suspend fun reassign(target: ClaimTarget, to: PeerIdentity, by: PeerIdentity, timeout: Duration = 10.seconds): ClaimToken? {
        val (entry, after) = propose(ClaimCommand.Reassign(target, to, by), timeout) ?: return null
        return (after as? ClaimStatus.Held)?.takeIf { it.holder == to && it.token == entry.token() }?.token
    }

    /** True if the complete committed and the target is done by [by]. */
    suspend fun complete(target: ClaimTarget, by: PeerIdentity, token: ClaimToken, timeout: Duration = 10.seconds): Boolean =
        propose(ClaimCommand.Complete(target, by, token), timeout)?.second == ClaimStatus.Done(by)

    /**
     * Proposes [command] and returns its entry with the target's status right after it, or null
     * if it didn't commit and fold within [timeout]. A proposal the cluster refused is null too.
     */
    private suspend fun propose(command: ClaimCommand, timeout: Duration): Pair<LogEntry, ClaimStatus>? =
        withTimeoutOrNull(timeout) {
            val entry = try {
                cluster.propose(ClaimCommand.encode(command))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // LeadershipLost, NotLeader (closed) and the like: the outcome is unknown, which
                // the caller reports as unavailable.
                return@withTimeoutOrNull null
            }
            val status = verdicts.first { entry.index in it }.getValue(entry.index)
            entry to status
        }

    private fun fold(committed: Committed) {
        val next = tableState.value.fold(committed)
        tableState.value = next
        if (committed !is Committed.Entry) return
        val command = runCatching { ClaimCommand.decode(committed.entry.command) }.getOrNull() ?: return
        val target = command.target() ?: return
        val status = next.status(target)
        verdicts.update { (it + (committed.entry.index to status)).trimmed() }
        if (command is ClaimCommand.Claim && (target to command.by) in abandoned.value) {
            releaseIfWon(target, command.by, status, ownTokenUnknown = false, token = committed.entry.token())
        }
    }

    /**
     * Releases an abandoned claim that [status] shows this client won. A late commit names its
     * own token; right after a timeout the token is unknown, so any hold by [by] is taken as it.
     */
    private fun releaseIfWon(
        target: ClaimTarget,
        by: PeerIdentity,
        status: ClaimStatus,
        ownTokenUnknown: Boolean,
        token: ClaimToken? = null,
    ) {
        if (status !is ClaimStatus.Held || status.holder != by) return
        if (!ownTokenUnknown && status.token != token) return
        abandoned.update { it - (target to by) }
        // Not on the collector: the release's own commit arrives through it.
        scope.launch { release(target, by, status.token) }
    }

    private fun Map<Long, ClaimStatus>.trimmed(): Map<Long, ClaimStatus> =
        if (size <= VERDICTS_KEPT) this else entries.sortedBy { it.key }.drop(size - VERDICTS_KEPT).associate { it.toPair() }

    private companion object {
        const val VERDICTS_KEPT = 256
    }
}

private fun LogEntry.token() = ClaimToken(term, index)

private fun ClaimCommand.target(): ClaimTarget? = when (this) {
    is ClaimCommand.Claim -> target
    is ClaimCommand.Release -> target
    is ClaimCommand.Reassign -> target
    is ClaimCommand.Complete -> target
    is ClaimCommand.Depart -> null
}
