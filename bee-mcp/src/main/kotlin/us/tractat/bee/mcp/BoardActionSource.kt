package us.tractat.bee.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimToken
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.core.RecordId
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The board-backed [ActionSource] (spec §5.2, §6.3). Invariant: **a wait returns a record only
 * under a token its own claim won or a `Reassign` granted this session, and any other claim this
 * bridge holds but didn't hand over is given back, whether or not a wait reaches it.** The bridge sweeps the live link's table as it catches
 * up (tractat-us/bee#31), so a late win or a claim appended just before its link died is given back
 * on the next link. A wait that reaches such a claim first gives it back itself. A win, a wait's or
 * `bee_claim`'s, is kept while its claim is still in flight, so neither the sweep nor another wait
 * gives it back first.
 *
 * Records are addressed to a surface, and any session may wait on any surface, so every candidate
 * (`record:<author>:<seq>`) is claimed through Raft before it's returned. A record another session
 * holds, or one this bridge already handed to a caller, is passed over for good. The one exception
 * is a surface the host opened: a wait or a list on it is refused ([refuseHosts]).
 *
 * **A claim of this bridge's own that nobody received is given back, then claimed again.** It
 * arises when a claim timed out and won late, when its link died after the voter appended it, or
 * when a release of it couldn't commit. The retry is told it lost to this very session; the target
 * is held under a token no caller has. The wait marks that token as being given back, releases it,
 * and claims the record again under a fresh token. So a release of the old token still on its way
 * to the voter is rejected, and can never free a record the wait returned. A target that reads free
 * retries. A token another claim of this bridge has won or may still win is waited out.
 *
 * **A record whose claim a person reassigned to this session is returned too** (spec §6.4): the
 * wait's claim loses to this very session, and the table shows a `Reassign` granted the hold. The
 * wait takes it into its hands ([OwnClaim.Reassigned]) and returns it under the grant's token, as
 * for a win. It does so even past its cursor, which names the record from when another held it,
 * and an open wait takes it as the `Reassign` commits ([BeeBridge.reassignedToSelf]). If the wait
 * ends before returning it, the hold stays this session's, for its next wait.
 *
 * A win is never lost at the wait's edge. If the bound lands just as the claim wins, the record
 * is handed over; if the wait ends any other way after the win (a cancel, an exception), the
 * claim is released under its token, queued until a claims link is up. A retirement met mid-wait
 * ends the wait with the bridge's refusal, which names the retired identity, why, and its
 * orphaned claims. While the claims link is down, the wait holds for it rather than failing.
 *
 * The cursor names every record on the surface this caller has passed: returned, or held by
 * another. It's opaque to callers, and grows with the surface's records, which v1 accepts.
 */
class BoardActionSource internal constructor(
    private val bridge: BeeBridge,
    /** Test seam: runs once a claim is told it won, before its token is taken into this wait's hands. */
    private val beforeReserve: suspend () -> Unit = {},
    /** Test seam: runs with what each of this wait's [BeeBridge.giveBack] calls found, once it returns. */
    private val afterGiveBack: (OwnClaim) -> Unit = {},
    /** Test seam: runs once [claimNext] returns a win, within the wait's bound. */
    private val afterWin: suspend () -> Unit,
) : ActionSource {
    constructor(bridge: BeeBridge) : this(bridge, afterWin = {})

    override suspend fun next(surfaceId: String, cursor: String?, timeout: Duration): WaitResult {
        bridge.touch()
        refuseHosts(bridge.board.value, surfaceId)
        val passed = decode(cursor).toMutableSet()
        bridge.waiting(on = true)
        var won: Won? = null
        var handed = false
        try {
            withTimeoutOrNull(timeout) {
                won = claimNext(surfaceId, passed)
                afterWin()
            }
            // The bound can land as the claim wins: a win is handed over, never dropped.
            val got = won ?: return WaitResult(record = null, timedOut = true, cursor = encode(passed))
            currentCoroutineContext().ensureActive()
            val state = bridge.board.value
            val record = json(got.id, got.record, got.token, got.record.basis.completeIn(state, surfaceId))
            bridge.handedOver(target(got.id), got.token)
            handed = true
            return WaitResult(record = record, timedOut = false, cursor = encode(passed + got.id.dot))
        } finally {
            withContext(NonCancellable) {
                if (!handed) won?.let { unreceived(it) }
                bridge.waiting(on = false)
            }
        }
    }

    /** A win this wait couldn't return is released; a reassigned claim is left this session's. */
    private suspend fun unreceived(w: Won) {
        if (w.reassigned) bridge.unreserve(target(w.id), w.token) else bridge.releaseUnreceived(target(w.id), w.by, w.token)
    }

    /**
     * Claims candidates on [surfaceId] in order until one is this wait's, adding each held by
     * another, or handed over already, to [passed]. A retirement propagates as the bridge's
     * [BridgeRefusal].
     */
    private suspend fun claimNext(surfaceId: String, passed: MutableSet<Dot>): Won {
        while (true) {
            val (id, record) = nextCandidate(surfaceId, passed)
            val target = target(id)
            val by = bridge.self
            // A win is taken into this wait's hands while its claim is still in flight, so nothing gives it back first.
            val outcome = try {
                bridge.claim(target) { o ->
                    if (o is ClaimOutcome.Won) {
                        beforeReserve()
                        bridge.reserve(target, o.token)
                    }
                    o
                }
            } catch (_: ClaimsOfflineRefusal) {
                // The claims link is being rebuilt: the wait holds until it's back, within its bound.
                bridge.awaitClaims()
                continue
            }
            when (outcome) {
                is ClaimOutcome.Won -> return Won(id, record, by, outcome.token, reassigned = false)
                is ClaimOutcome.Lost -> when (val own = ownClaim(target, outcome, by)) {
                    Own.Pass -> passed += id.dot
                    Own.Retry -> Unit
                    is Own.Granted -> return Won(id, record, by, own.token, reassigned = true)
                }
                // No quorum: the record stays a candidate, tried again shortly.
                ClaimOutcome.Unavailable -> delay(RETRY)
                // Retired as the claim committed, as a `/clear` mid-claim does: the next try
                // re-identifies first, and refuses.
                ClaimOutcome.Retired -> Unit
            }
        }
    }

    /** What a claim [lost] to [by] itself means for the record. */
    private sealed interface Own {
        data object Pass : Own

        data object Retry : Own

        /** A `Reassign` granted it to this session under [token], now in this wait's hands. */
        data class Granted(val token: ClaimToken) : Own
    }

    private suspend fun ownClaim(target: ClaimTarget, lost: ClaimOutcome.Lost, by: PeerIdentity): Own {
        if (lost.holder != by) return Own.Pass
        // Offline since the claim was judged: claim it again, which holds for the link.
        val table = bridge.claimTable() ?: return Own.Retry
        return when (val status = table.status(target)) {
            // Released since the claim was judged: claim it again.
            ClaimStatus.Free -> Own.Retry
            is ClaimStatus.Held ->
                if (status.holder != by) {
                    Own.Pass
                } else {
                    when (val own = bridge.giveBack(table, target, by, status.token).also(afterGiveBack)) {
                        OwnClaim.HandedOver -> Own.Pass
                        OwnClaim.InReach -> Own.Retry.also { bridge.awaitOutOfReach(target, status.token) }
                        // Claim it again, under a fresh token.
                        OwnClaim.GivenBack -> Own.Retry
                        OwnClaim.Queued -> Own.Retry.also { delay(RETRY) }
                        is OwnClaim.Reassigned -> Own.Granted(own.token)
                    }
                }
            is ClaimStatus.Orphaned, is ClaimStatus.Done -> Own.Pass
        }
    }

    override fun list(surfaceId: String, since: String?): ActionPage {
        bridge.touch()
        val passed = decode(since)
        val state = bridge.board.value
        refuseHosts(state, surfaceId)
        val fresh = state.records(surfaceId).filter { (id, _) -> id.dot !in passed }
        return ActionPage(
            records = fresh.map { (id, record) -> json(id, record, token = null, basisComplete = record.basis.completeIn(state, surfaceId)) },
            cursor = encode(passed + fresh.map { it.first.dot }),
        )
    }

    /**
     * The first record on [surfaceId] not in [passed], or in it but reassigned to this session
     * since, suspending until one arrives.
     */
    private suspend fun nextCandidate(surfaceId: String, passed: Set<Dot>): Pair<RecordId, ActionRecord> =
        bridge.board.combine(bridge.reassignedToSelf) { state, granted -> candidate(state, surfaceId, passed, granted) }.filterNotNull().first()

    private fun candidate(state: BoardState, surfaceId: String, passed: Set<Dot>, granted: Set<ClaimTarget>) =
        state.records(surfaceId).firstOrNull { (id, _) -> id.dot !in passed || target(id) in granted }
            ?.also { refuseHosts(state, surfaceId) }

    /**
     * Refuses a surface the host opened, such as a join request (spec §6.2, bee#72). The host
     * handles its records itself, without a claim, as their only handler, so an agent has no
     * business with them, and a record it took would hand it the code a person typed.
     *
     * This is a visibility rule for agents, not an access gate in bee#68's sense: it keys on the
     * kind the surface's opener declares, and only a well-behaved bridge keeps it. A board writer
     * can still read the records off its own replica. The host doesn't rely on it.
     */
    private fun refuseHosts(state: BoardState, surfaceId: String) {
        val openedBy = state.meta(surfaceId)?.openedBy ?: return
        if (openedBy.kind == PeerKind.Host) {
            throw BridgeRefusal("Surface $surfaceId is the host's ($openedBy): the host handles its records itself, so no session waits on or lists them.")
        }
    }

    /** A record this wait has in hand under [token]: won by its own claim, or [reassigned] to its session. */
    private data class Won(val id: RecordId, val record: ActionRecord, val by: PeerIdentity, val token: ClaimToken, val reassigned: Boolean)

    companion object {
        /** How long a wait backs off after a claim found no quorum. */
        val RETRY: Duration = 500.milliseconds

        /** The claim target for record [id]: `record:<author>:<seq>`. */
        fun target(id: RecordId): ClaimTarget = ClaimTarget("record:${recordId(id)}")

        /** A record's id as tools show it: `<author>:<seq>`. */
        fun recordId(id: RecordId): String = "${id.dot.replica.value}:${id.dot.seq}"

        /** Reads a record id [recordId] wrote. */
        fun parseRecordId(text: String): RecordId {
            val split = text.lastIndexOf(':')
            require(split > 0) { "Not a record id: $text" }
            val seq = requireNotNull(text.substring(split + 1).toLongOrNull()) { "Not a record id: $text" }
            return RecordId(Dot(ReplicaId(text.substring(0, split)), seq))
        }

        /** A claim token as tools show it: `<term>:<index>`. */
        fun tokenText(token: ClaimToken): String = "${token.term}:${token.index}"

        /** Reads a token [tokenText] wrote. */
        fun parseToken(text: String): ClaimToken {
            val parts = text.split(':')
            val numbers = parts.mapNotNull { it.toLongOrNull() }
            require(parts.size == 2 && numbers.size == 2) { "Not a claim token: $text" }
            return ClaimToken(numbers[0], numbers[1])
        }

        private fun json(id: RecordId, record: ActionRecord, token: ClaimToken?, basisComplete: Boolean): JsonObject = buildJsonObject {
            put("recordId", recordId(id))
            put("surfaceId", record.surfaceId)
            put("componentId", record.componentId)
            put("actor", record.actor.toString())
            put("name", record.name)
            put("context", record.context)
            put("at", record.at.toString())
            token?.let { put("token", tokenText(it)) }
            put("basisComplete", basisComplete)
        }

        private fun encode(passed: Set<Dot>): String =
            passed.sortedWith(compareBy<Dot>({ it.replica.value }, { it.seq })).joinToString(",") { "${it.replica.value}:${it.seq}" }

        private fun decode(cursor: String?): Set<Dot> =
            cursor?.takeIf { it.isNotEmpty() }?.split(',')?.map { parseRecordId(it).dot }?.toSet().orEmpty()
    }
}
