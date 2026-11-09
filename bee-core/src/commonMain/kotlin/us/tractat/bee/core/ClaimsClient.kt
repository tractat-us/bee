package us.tractat.bee.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.ForwardedProposalFailedException
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a claim came to. */
sealed interface ClaimOutcome {
    /** This claim committed first: hold [token] and pass it to `release` or `complete`. */
    data class Won(val token: ClaimToken) : ClaimOutcome

    /** Another claim got there first, and [holder] holds the target, or held it to done. */
    data class Lost(val holder: PeerIdentity) : ClaimOutcome

    /** The claimant was retired when its claim committed, so the claim applied as rejected. */
    data object Retired : ClaimOutcome

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
 * this client. Each claim carries its own [ProposalId], and the client judges a claim only at the
 * first committed entry carrying that id. So another tab claiming as the same `human:` identity
 * (spec §5.1), an earlier win, or a retry is never mistaken for it. A late win of a claim reported
 * [ClaimOutcome.Unavailable], or cancelled by its caller, is released with that entry's token,
 * retried until the table shows the target no longer held under it ([unreleased] names any the
 * retries couldn't free).
 *
 * It collects [cluster]'s committed stream, so it must be the stream's only collector, and is
 * built before the client's first join (the stream has no replay).
 *
 * @param node the `clientNodeId` [cluster] was built with. It makes each [ProposalId] unique across
 *   the cluster.
 * @param random draws this client's [ProposalId.incarnation], once. Two clients under one [node]
 *   must draw different incarnations, so don't seed it from [node].
 */
class ClaimsClient(
    private val cluster: ClusterClient,
    private val node: NodeId,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
    random: Random,
) {
    private val incarnation = random.nextLong()
    private val serials = MutableStateFlow(0L)

    private val tableState = MutableStateFlow(ClaimTable.EMPTY)
    val table: StateFlow<ClaimTable> = tableState.asStateFlow()

    /** The target's status right after each recent entry, so a proposer reads its own verdict exactly. */
    private val verdicts = MutableStateFlow<Map<Long, ClaimStatus>>(emptyMap())

    /**
     * This client's claims, from proposal until [claim] returns, and past that for an abandoned
     * one until it commits. An abandoned claim that never commits stays here.
     */
    private val proposals = MutableStateFlow<Map<ProposalId, Proposal>>(emptyMap())

    private val unreleasedState = MutableStateFlow<Set<Pair<ClaimTarget, ClaimToken>>>(emptySet())

    /** Late wins whose release didn't take within [RELEASE_ATTEMPTS] tries: still held, by no caller. */
    val unreleased: StateFlow<Set<Pair<ClaimTarget, ClaimToken>>> = unreleasedState.asStateFlow()

    /** Claim latencies, in proposal order, for the spike's report. */
    private val latencyLog = MutableStateFlow<List<Duration>>(emptyList())
    val latencies: StateFlow<List<Duration>> = latencyLog.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cluster.committed.collect(::fold)
        }
    }

    suspend fun claim(target: ClaimTarget, by: PeerIdentity, timeout: Duration = 10.seconds): ClaimOutcome {
        val id = ProposalId(node.value, incarnation, serials.updateAndGet { it + 1 })
        proposals.update { it + (id to Proposal(target, by)) }
        val started = clock()
        val verdict = try {
            withTimeoutOrNull(timeout) {
                proposeOrNull(ClaimCommand.Claim(target, by, id)) ?: return@withTimeoutOrNull null
                proposals.mapNotNull { it[id]?.verdict }.first()
            }
        } catch (e: CancellationException) {
            // The caller gave up: nobody will hear a win, so it is abandoned like a timeout.
            abandon(id)
            throw e
        }
        if (verdict == null) {
            abandon(id)
            return ClaimOutcome.Unavailable
        }
        proposals.update { it - id }
        latencyLog.update { (it + (clock() - started)).takeLast(VERDICTS_KEPT) }
        return verdict.outcome
    }

    /**
     * True if holder [by]'s release of [target] under its current [token] committed and freed it;
     * false if it was rejected or unavailable. An orphan is released with [releaseOrphan].
     */
    suspend fun release(target: ClaimTarget, by: PeerIdentity, token: ClaimToken, timeout: Duration = 10.seconds): Boolean =
        propose(ClaimCommand.Release(target, by, token), timeout)?.second == ClaimStatus.Free

    /**
     * True if [by]'s decision to release orphaned [target] committed and freed it. [orphan] is the
     * orphaned hold's token, naming the orphaning decided: once the target is orphaned under
     * another, the release is rejected (bee#83).
     */
    suspend fun releaseOrphan(target: ClaimTarget, by: PeerIdentity, orphan: ClaimToken, timeout: Duration = 10.seconds): Boolean =
        propose(ClaimCommand.Release(target, by, token = null, orphan = orphan), timeout)?.second == ClaimStatus.Free

    /**
     * The new token if the reassign of orphaned [target] committed, else null. [orphan] is the
     * orphaned hold's token, as for [releaseOrphan].
     */
    suspend fun reassign(target: ClaimTarget, to: PeerIdentity, by: PeerIdentity, orphan: ClaimToken, timeout: Duration = 10.seconds): ClaimToken? {
        val (entry, after) = propose(ClaimCommand.Reassign(target, to, by, orphan), timeout) ?: return null
        return (after as? ClaimStatus.Held)?.takeIf { it.holder == to && it.token == entry.token() }?.token
    }

    /** True if the complete committed and the target is done by [by]. */
    suspend fun complete(target: ClaimTarget, by: PeerIdentity, token: ClaimToken, timeout: Duration = 10.seconds): Boolean =
        propose(ClaimCommand.Complete(target, by, token), timeout)?.second == ClaimStatus.Done(by)

    /**
     * Proposes [command] and returns its entry with the target's status right after it, or null
     * if it didn't commit and fold within [timeout], or the cluster couldn't take it.
     */
    private suspend fun propose(command: ClaimCommand, timeout: Duration): Pair<LogEntry, ClaimStatus>? =
        withTimeoutOrNull(timeout) {
            val entry = proposeOrNull(command) ?: return@withTimeoutOrNull null
            val status = verdicts.first { entry.index in it }.getValue(entry.index)
            entry to status
        }

    /**
     * The committed entry, or null on the transport failures `RaftNode.propose` documents, after
     * which the outcome is unknown. Anything else (a refused size, an identity misuse) is a bug
     * and propagates.
     */
    private suspend fun proposeOrNull(command: ClaimCommand): LogEntry? =
        try {
            cluster.propose(ClaimCommand.encode(command))
        } catch (_: LeadershipLostException) {
            null
        } catch (_: ForwardedProposalFailedException) {
            null
        } catch (_: NotLeaderException) {
            null
        }

    /** Gives up on claim [id]: releases it if it already won, or marks it so its late win is. */
    private fun abandon(id: ProposalId) {
        var won: Verdict? = null
        proposals.update { current ->
            val proposal = current.getValue(id)
            won = proposal.verdict?.takeIf { it.won }
            if (proposal.verdict != null) current - id else current + (id to proposal.copy(abandoned = true))
        }
        won?.let { releaseLateWin(it) }
    }

    private fun fold(committed: Committed) {
        val (next, command) = tableState.value.applied(committed)
        tableState.value = next
        if (committed !is Committed.Entry || command == null) return
        val target = command.target() ?: return
        val status = next.status(target)
        verdicts.update { (it + (committed.entry.index to status)).trimmed() }
        if (command is ClaimCommand.Claim) judge(command, Verdict(target, command.by, committed.entry.token(), status, next.retired(command.by)))
    }

    /** Records the verdict of this client's own claim, at the first entry that carries its id. */
    private fun judge(claim: ClaimCommand.Claim, verdict: Verdict) {
        var lateWin: Verdict? = null
        proposals.update { current ->
            lateWin = null
            val proposal = current[claim.proposal]
            when {
                proposal == null || proposal.verdict != null -> current // not ours, or a duplicate
                proposal.target != claim.target || proposal.by != claim.by -> current // not the claim this id named
                proposal.abandoned -> {
                    lateWin = verdict.takeIf { it.won }
                    current - claim.proposal
                }
                else -> current + (claim.proposal to proposal.copy(verdict = verdict))
            }
        }
        lateWin?.let { releaseLateWin(it) }
    }

    /**
     * Releases a win nobody was told about, retrying until the table shows the target is no
     * longer held under its token. Not on the collector: the release's own commit arrives there.
     */
    private fun releaseLateWin(win: Verdict) {
        val held = ClaimStatus.Held(win.by, win.token)
        scope.launch {
            repeat(RELEASE_ATTEMPTS) {
                if (table.value.status(win.target) != held) return@launch
                if (release(win.target, win.by, win.token)) return@launch
            }
            if (table.value.status(win.target) == held) unreleasedState.update { it + (win.target to win.token) }
        }
    }

    private fun Map<Long, ClaimStatus>.trimmed(): Map<Long, ClaimStatus> =
        if (size <= VERDICTS_KEPT) this else entries.sortedBy { it.key }.drop(size - VERDICTS_KEPT).associate { it.toPair() }

    private data class Proposal(val target: ClaimTarget, val by: PeerIdentity, val verdict: Verdict? = null, val abandoned: Boolean = false)

    /** A claim's result at the first entry carrying its id: [after] is the target's status right after it. */
    private data class Verdict(
        val target: ClaimTarget,
        val by: PeerIdentity,
        val token: ClaimToken,
        val after: ClaimStatus,
        val retired: Boolean,
    ) {
        val won: Boolean get() = after == ClaimStatus.Held(by, token)

        val outcome: ClaimOutcome get() = when {
            won -> ClaimOutcome.Won(token)
            retired -> ClaimOutcome.Retired
            after is ClaimStatus.Held -> ClaimOutcome.Lost(after.holder)
            after is ClaimStatus.Orphaned -> ClaimOutcome.Lost(after.holder)
            after is ClaimStatus.Done -> ClaimOutcome.Lost(after.by)
            // A claim of a free target by a claimant who isn't retired always wins.
            else -> error("claim of $target by $by rejected with the target free and the claimant not retired")
        }
    }

    private companion object {
        const val VERDICTS_KEPT = 256
        const val RELEASE_ATTEMPTS = 5
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
