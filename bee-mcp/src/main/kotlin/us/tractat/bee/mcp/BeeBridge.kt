package us.tractat.bee.mcp

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimToken
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

/**
 * One Claude session on the board (spec §5.2): its replica, its claims, its identity and its
 * presence. Invariant: **no write leaves the bridge under a retired identity.** Before every write
 * it checks its own claim table; if the host has retired the identity, it takes a fresh one and
 * refuses the write, naming the retired identity, why, and the claims left orphaned.
 *
 * The bridge validates every surface it writes against the Notions catalog (A11) and refuses a
 * malformed one, naming the id or type, with the board unchanged.
 *
 * Its claims run over [claims], which rebuilds a dropped link. While no link is up, every claim
 * operation is refused with [CLAIMS_OFFLINE]. The retirement check reads both the claim table and
 * the board's mirror of every committed `Depart`, so it holds offline too, and while a fresh link
 * catches up from an empty table.
 *
 * The bridge outlives its links, so it keeps the record of which claims it handed to a caller
 * ([reserve], [handedOver]): a claim of its own that nobody received is given back ([giveBack])
 * and claimed again under a fresh token, never handed over under the old one.
 *
 * **Every claim it holds that nobody received is given back, whether or not a wait reaches it.**
 * It sweeps the live link's table as the link catches up (tractat-us/bee#31): a claim held as this
 * bridge, under a token no wait has in hand and no caller received, with no claim of its target in
 * flight here, is marked as being given back and released, queued until it commits. A claim in
 * flight is skipped, because between its verdict and its keep ([reserve], or [handedOver] for
 * `bee_claim`) its win is in nobody's hands yet; once it leaves flight the sweep looks again. A
 * wait's [giveBack] follows the same rule, so no win is given back from under the claim that won it.
 *
 * **A claim reassigned to this identity is handed to the session, never given back** (Task 11,
 * spec §6.4, bee#31). It's held as this bridge, and no wait or caller here received it, just like
 * a stranded late win, so the bridge's own record can't tell the two apart. The claim table can: a
 * hold a `Reassign` granted says so ([ClaimTable.reassigned]). The sweep leaves such a hold alone,
 * and a wait on its record's surface that finds it ([giveBack]) takes it into its hands
 * ([OwnClaim.Reassigned]) and returns the record under the grant's token, as for a win.
 *
 * @param author the [client]'s CRDT author (`mcp-<uuid>`), which every write names.
 * @param scope runs the sweep, for the bridge's lifetime.
 */
@Suppress("TooManyFunctions")
class BeeBridge(
    private val client: BoardClient,
    private val author: ReplicaId,
    val claims: ClaimsConnection,
    private val identity: BridgeIdentity,
    private val presence: Presence,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
) {
    private val retiring = Mutex()

    /**
     * A claim this bridge won, by target and token: in a wait's hands, handed to a caller, or
     * being given back. A claim being given back never returns to a wait's hands.
     */
    private enum class Reservation { InHand, HandedOver, GivingBack }

    /**
     * What this bridge's claims are to it, in one value so one update reads it all: the
     * [reservations] of the claims it won, and how many claims of each target are in flight
     * ([claiming]), from proposal until the win, if any, is reserved or handed over.
     */
    private data class Ledger(
        val reservations: Map<Pair<ClaimTarget, ClaimToken>, Reservation> = emptyMap(),
        val claiming: Map<ClaimTarget, Int> = emptyMap(),
    ) {
        fun with(key: Pair<ClaimTarget, ClaimToken>, reservation: Reservation) = copy(reservations = reservations + (key to reservation))

        fun entering(target: ClaimTarget) = copy(claiming = claiming + (target to (claiming[target] ?: 0) + 1))

        fun leaving(target: ClaimTarget): Ledger {
            val left = claiming.getValue(target) - 1
            return copy(claiming = if (left == 0) claiming - target else claiming + (target to left))
        }

        /** Nobody could still take [key]: it isn't reserved, and no claim of its target is in flight. */
        fun unclaimed(key: Pair<ClaimTarget, ClaimToken>) = key !in reservations && key.first !in claiming

        /**
         * What [key] is to this bridge's other claims. It's in reach while a wait has it in hand,
         * or while it's unreserved and a claim of its target is in flight, whose win isn't in
         * anyone's hands yet. A key already being given back is givable whatever is in flight.
         */
        fun standing(key: Pair<ClaimTarget, ClaimToken>): Standing = when (reservations[key]) {
            Reservation.HandedOver -> Standing.HandedOver
            Reservation.InHand -> Standing.InReach
            Reservation.GivingBack -> Standing.Givable
            null -> if (key.first in claiming) Standing.InReach else Standing.Givable
        }
    }

    private enum class Standing { HandedOver, InReach, Givable }

    private val ledger = MutableStateFlow(Ledger())

    @Volatile private var statusLine: String? = null

    /** The board as this bridge holds it. */
    val board: StateFlow<BoardState> get() = client.state

    /** The identity the bridge acts as now. */
    val self: PeerIdentity get() = identity.current.value

    /** Whether a claims link is up. */
    val claimsOnline: Boolean get() = claims.link.value != null

    /** Everyone attached, as this bridge's replica sees them. */
    suspend fun roster(): List<PresenceEntry> = presence.roster.live()

    /** Puts this session on the board's presence from the start, not only from its first tool call. */
    suspend fun announce() = presence.update(self, statusLine, clock(), waiting = false)

    /** Counts a read-only call as acting, so a session that only reads is never quiet. */
    fun touch() = presence.touch(clock())

    /** Suspends until [target]'s status in the live link's table satisfies [done]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitStatus(target: ClaimTarget, done: (ClaimStatus) -> Boolean) {
        claims.link.filterNotNull().flatMapLatest { it.client.table }.first { done(it.status(target)) }
    }

    /** This bridge's claim table, as the live link has folded it, or null while claims are offline. */
    fun claimTable(): ClaimTable? = claims.link.value?.client?.table?.value

    /**
     * The targets a `Reassign` granted this session that no wait or caller here has yet, as the
     * live link's table and this bridge's record change. Empty while claims are offline. A wait
     * takes such a record even past its cursor, since it passed it over while another held it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reassignedToSelf: Flow<Set<ClaimTarget>> =
        claims.link.flatMapLatest { it?.client?.table ?: flowOf(null) }.combine(ledger) { table, ledger ->
            table?.let { granted(it, ledger, self) }.orEmpty()
        }.distinctUntilChanged()

    private fun granted(table: ClaimTable, ledger: Ledger, who: PeerIdentity): Set<ClaimTarget> =
        table.targets.filter { (target, status) ->
            status is ClaimStatus.Held && status.holder == who && table.reassigned(target) && (target to status.token) !in ledger.reservations
        }.keys

    /** Opens surface [surfaceId] with [components] and [data]. */
    suspend fun open(surfaceId: String, title: String, components: List<JsonObject>, data: JsonObject) {
        val by = beforeWrite()
        write(surfaceId) { state ->
            require(state.surfaceDoc(surfaceId) == null) { "Surface $surfaceId is already on the board; update or reopen it." }
            state.openSurface(author, surfaceId, SurfaceMeta(title, NOTIONS_CATALOG_ID, by, clock()), components, data)
        }
    }

    /** Upserts [components] by id, then writes each of [data]'s pointers in order. */
    suspend fun update(surfaceId: String, components: List<JsonObject>, data: List<Pair<String, JsonElement>>) {
        beforeWrite()
        write(surfaceId) { state ->
            requireNotNull(state.surfaceDoc(surfaceId)) { "No surface $surfaceId on the board." }
            // Each write reads the state the previous ones left (A13); the patch is their deltas joined.
            val writes = buildList<(BoardState) -> Patch<BoardState>> {
                if (components.isNotEmpty()) add { it.updateComponents(author, surfaceId, components) }
                data.forEach { (path, value) -> add { it.updateData(author, surfaceId, path, value) } }
            }
            val (_, delta) = writes.fold(state to BoardState.EMPTY) { (s, d), w ->
                val step = w(s).delta
                s.piece(step) to d.piece(step)
            }
            Patch(delta)
        }
    }

    /** Closes surface [surfaceId]; it keeps its content and records. */
    suspend fun close(surfaceId: String) {
        beforeWrite()
        client.mutate { it.closeSurface(author, surfaceId, clock()) }
    }

    /** Closes every open surface and deletes none. Returns the ids it closed. */
    suspend fun clear(): List<String> {
        beforeWrite()
        val open = board.value.openSurfaces()
        open.forEach { id -> client.mutate { it.closeSurface(author, id, clock()) } }
        return open
    }

    /** Claims [target] for the caller to keep, as `bee_claim` does: a win is recorded as handed over. */
    suspend fun claim(target: ClaimTarget): ClaimOutcome = claim(target) { keptForCaller(target, it) }

    /** `bee_claim`'s keep: records a win of [target] as handed over to the caller, and answers [outcome]. */
    internal fun keptForCaller(target: ClaimTarget, outcome: ClaimOutcome): ClaimOutcome {
        if (outcome is ClaimOutcome.Won) handedOver(target, outcome.token)
        return outcome
    }

    /**
     * Claims [target] as this bridge, and settles the outcome with [keep] while the claim is still
     * in flight, so the sweep can't give a win back first. [keep] takes a win into a wait's hands
     * ([reserve]) or records it handed over ([handedOver]); a win it leaves is one nobody
     * received, and the sweep gives it back. A retired identity re-identifies and is refused.
     */
    internal suspend fun <T> claim(target: ClaimTarget, keep: suspend (ClaimOutcome) -> T): T {
        ledger.update { it.entering(target) }
        try {
            val outcome = live().client.claim(target, beforeWrite())
            // Retired as the claim committed: re-identify now, which refuses this call.
            if (outcome == ClaimOutcome.Retired) beforeWrite()
            return keep(outcome)
        } finally {
            ledger.update { it.leaving(target) }
        }
    }

    /** Releases [target]; [token] may be null only for an orphaned target. */
    suspend fun release(target: ClaimTarget, token: ClaimToken?): Boolean = live().client.release(target, beforeWrite(), token)

    /** Marks [target] done under [token]. */
    suspend fun done(target: ClaimTarget, token: ClaimToken): Boolean = live().client.complete(target, beforeWrite(), token)

    /** Releases [target] under [token] without the retirement check: the hand-back of a claim nobody received. */
    internal suspend fun releaseUnreceived(target: ClaimTarget, holder: PeerIdentity, token: ClaimToken) {
        ledger.update { it.with(target to token, Reservation.GivingBack) }
        claims.releaseUnreceived(ClaimsConnection.Unreceived(target, holder, token))
    }

    /**
     * Takes [token] on [target], just won by the wait's own claim, into that wait's hands. Call it
     * from the claim's keep, while the claim is still in flight. It can't find the token taken:
     * a token is won by exactly one claim, and while a claim of its target is in flight nothing
     * marks it, since the sweep skips it and [giveBack] answers [OwnClaim.InReach]. If it ever
     * does, it throws rather than let the wait pass a record it won.
     */
    internal fun reserve(target: ClaimTarget, token: ClaimToken) {
        val key = target to token
        ledger.update {
            check(key !in it.reservations) {
                "$token on $target was just won by a wait's claim, yet is already ${it.reservations[key]}: " +
                    "nothing may mark a token while a claim of its target is in flight."
            }
            it.with(key, Reservation.InHand)
        }
    }

    /**
     * Takes [token] on [target] out of a wait's hands, unreceived: a reassigned claim the wait
     * took ([OwnClaim.Reassigned]) but couldn't return. It stays this session's, for its next wait.
     */
    internal fun unreserve(target: ClaimTarget, token: ClaimToken) {
        ledger.update { if (it.reservations[target to token] == Reservation.InHand) it.copy(reservations = it.reservations - (target to token)) else it }
    }

    /** Records that [token] on [target] reached a caller. */
    internal fun handedOver(target: ClaimTarget, token: ClaimToken) {
        ledger.update { it.with(target to token, Reservation.HandedOver) }
    }

    /** Suspends until [token] on [target] is out of reach of this bridge's other claims: see [Ledger.standing]. */
    internal suspend fun awaitOutOfReach(target: ClaimTarget, token: ClaimToken) {
        ledger.first { it.standing(target to token) != Standing.InReach }
    }

    /**
     * Gives back this bridge's own claim of [target], held as [holder] under [token] in [table],
     * unless a caller received it or another claim of this bridge has it in reach: a wait has it
     * in hand, or a claim of [target] is in flight, whose win isn't in anyone's hands yet. That is
     * the rule the sweep follows. One update reads its standing and marks it as being given back;
     * then it's released. The caller claims the record again under a fresh token, and any release
     * of [token] still on its way to the voter is rejected.
     *
     * A hold a `Reassign` granted ([ClaimTable.reassigned]) is never given back: if nothing here
     * has it yet, the same update takes it into the caller's hands, and the caller returns it
     * ([OwnClaim.Reassigned]). [table] is the one the caller read [token] from, so the check
     * always runs against it.
     */
    internal suspend fun giveBack(table: ClaimTable, target: ClaimTarget, holder: PeerIdentity, token: ClaimToken): OwnClaim {
        val key = target to token
        if (table.reassigned(target) && table.status(target) == ClaimStatus.Held(holder, token)) {
            val taken = ledger.getAndUpdate { if (key in it.reservations) it else it.with(key, Reservation.InHand) }.reservations[key] == null
            if (taken) return OwnClaim.Reassigned(token)
        }
        val found = ledger.getAndUpdate { if (it.standing(key) == Standing.Givable) it.with(key, Reservation.GivingBack) else it }.standing(key)
        return when (found) {
            Standing.HandedOver -> OwnClaim.HandedOver
            Standing.InReach -> OwnClaim.InReach
            Standing.Givable ->
                if (claims.releaseUnreceived(ClaimsConnection.Unreceived(target, holder, token))) OwnClaim.GivenBack else OwnClaim.Queued
        }
    }

    init {
        scope.launch(CoroutineName("claims-sweep")) { sweep() }
    }

    /**
     * Gives back each claim held as this bridge that nobody could still take, as each table the
     * live link folds shows it, and again whenever the [ledger] changes. One update finds a key
     * unclaimed and marks it as being given back, so it's queued once, and a wait's [giveBack] of
     * it finds it givable. The release is queued on [claims], never tried here, so a failed try
     * can't leave a key marked and unqueued: the live link proposes it until it commits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun sweep() {
        claims.link.filterNotNull().flatMapLatest { it.client.table }.combine(ledger) { table, _ -> table }.collect { table ->
            val who = self
            table.targets.forEach { (target, status) ->
                if (status !is ClaimStatus.Held || status.holder != who) return@forEach
                // Reassigned to this session: a wait on its record's surface returns it ([giveBack]).
                if (table.reassigned(target)) return@forEach
                if (markUnclaimed(target to status.token)) claims.queue(ClaimsConnection.Unreceived(target, who, status.token))
            }
        }
    }

    /** Marks [key] as being given back if nobody could still take it; true if it did. */
    private fun markUnclaimed(key: Pair<ClaimTarget, ClaimToken>): Boolean =
        ledger.getAndUpdate { if (it.unclaimed(key)) it.with(key, Reservation.GivingBack) else it }.unclaimed(key)

    private fun live(): ClaimsLink = claims.link.value ?: throw ClaimsOfflineRefusal()

    /** Suspends until a claims link is up. */
    suspend fun awaitClaims() {
        claims.link.filterNotNull().first()
    }

    /** Sets the "working on…" line beside this session on the board. */
    suspend fun status(line: String?) {
        val by = beforeWrite()
        presence.update(by, line, clock(), waiting = false)
        statusLine = line
    }

    /** Marks this session as waiting on a surface, or no longer waiting; waiting is never quiet. */
    suspend fun waiting(on: Boolean) {
        presence.update(self, statusLine, clock(), waiting = on)
    }

    /**
     * The identity to write as, after the retirement check. If the host retired the current
     * identity, a fresh one takes its place (the `/clear` path) and the write is refused.
     */
    private suspend fun beforeWrite(): PeerIdentity = retiring.withLock {
        val who = self
        val table = claims.link.value?.client?.table?.value
        val mirrored = board.value
        val reason = table?.departReason(who) ?: mirrored.departures().firstOrNull { it.identity == who }?.reason
        if (reason == null) {
            presence.update(who, statusLine, clock(), waiting = false)
            return@withLock who
        }
        val orphaned = orphanedBy(who, table, mirrored)
        identity.retire(reason)
        presence.update(self, statusLine, clock(), waiting = false)
        throw BridgeRefusal(
            "This session was retired as $who ($reason), so the write was not made. " +
                "Claims it left orphaned: ${orphaned.ifEmpty { listOf("none") }.joinToString()}. " +
                "It now acts as $self; repeat the call to write as that identity.",
        )
    }

    /** The claims [who] left orphaned: from the table when it has caught up, else from the board's mirror. */
    private fun orphanedBy(who: PeerIdentity, table: ClaimTable?, mirrored: BoardState): List<String> {
        val fromTable = table?.targets.orEmpty().filterValues { it is ClaimStatus.Orphaned && it.holder == who }.keys.map { it.value }
        val fromBoard = mirrored.claims.entries.filterValues { it.status == ClaimView.ORPHANED && it.holder == who.toString() }.keys
        return (fromTable + fromBoard).distinct().sorted()
    }

    /**
     * Applies [f]'s patch only if surface [surfaceId] passes the Notions validator afterwards.
     * The check runs against the very state the patch lands on.
     */
    private suspend fun write(surfaceId: String, f: (BoardState) -> Patch<BoardState>) {
        var errors = emptyList<String>()
        var refused: IllegalArgumentException? = null
        client.mutateOrSkip { state ->
            val patch = try {
                f(state)
            } catch (bad: IllegalArgumentException) {
                refused = bad
                return@mutateOrSkip null
            }
            val after = state.piece(patch.delta).surfaceDoc(surfaceId)?.toSurfaceView(surfaceId)
            errors = after?.let(SurfaceValidator::errors).orEmpty()
            patch.takeIf { errors.isEmpty() }
        }
        refused?.let { throw BridgeRefusal(it.message ?: "The write was refused.") }
        if (errors.isNotEmpty()) throw BridgeRefusal("Surface $surfaceId was not written: ${errors.joinToString(" ")}")
    }
}

/** What [BeeBridge.giveBack] found for a claim of the bridge's own. */
internal sealed interface OwnClaim {
    /** A caller received it: the record is passed over. */
    data object HandedOver : OwnClaim

    /** Another claim of this bridge has won it or may still win it: wait that out. */
    data object InReach : OwnClaim

    /** Released, or no longer held under its token: claim the record again. */
    data object GivenBack : OwnClaim

    /** Its release didn't commit now and is queued until it does: back off, then claim again. */
    data object Queued : OwnClaim

    /** A `Reassign` granted it to this session, under [token], now in the caller's hands: return the record. */
    data class Reassigned(val token: ClaimToken) : OwnClaim
}

/** A write the bridge refused, with a message for the agent that names why. */
open class BridgeRefusal(message: String) : Exception(message)

/** A claim operation refused because no claims link is up: [CLAIMS_OFFLINE]. */
class ClaimsOfflineRefusal : BridgeRefusal(CLAIMS_OFFLINE)

/** What a claim operation answers while no claims link is up. */
const val CLAIMS_OFFLINE: String =
    "Claims offline: the bridge can't reach the host's claims voter and is reconnecting. Nothing was claimed; try again shortly."
