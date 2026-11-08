package us.tractat.bee.mcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
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
 * @param author the [client]'s CRDT author (`mcp-<uuid>`), which every write names.
 */
@Suppress("TooManyFunctions")
class BeeBridge(
    private val client: BoardClient,
    private val author: ReplicaId,
    val claims: ClaimsConnection,
    private val identity: BridgeIdentity,
    private val presence: Presence,
    private val clock: () -> Instant,
) {
    private val retiring = Mutex()

    /**
     * Claims this bridge won, by target and token: in a wait's hands, handed to a caller, or
     * being given back. A claim being given back never returns to a wait's hands.
     */
    private enum class Reservation { InHand, HandedOver, GivingBack }
    private val reservations = MutableStateFlow<Map<Pair<ClaimTarget, ClaimToken>, Reservation>>(emptyMap())

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

    /** [target]'s status in this bridge's claim table, or null while claims are offline. */
    fun claimStatus(target: ClaimTarget): ClaimStatus? = claims.link.value?.client?.table?.value?.status(target)

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

    /** Claims [target] as this bridge. A retired identity re-identifies and is refused. */
    suspend fun claim(target: ClaimTarget): ClaimOutcome {
        val outcome = live().client.claim(target, beforeWrite())
        // Retired as the claim committed: re-identify now, which refuses this call.
        if (outcome == ClaimOutcome.Retired) beforeWrite()
        return outcome
    }

    /** Releases [target]; [token] may be null only for an orphaned target. */
    suspend fun release(target: ClaimTarget, token: ClaimToken?): Boolean = live().client.release(target, beforeWrite(), token)

    /** Marks [target] done under [token]. */
    suspend fun done(target: ClaimTarget, token: ClaimToken): Boolean = live().client.complete(target, beforeWrite(), token)

    /** Releases [target] under [token] without the retirement check: the hand-back of a claim nobody received. */
    internal suspend fun releaseUnreceived(target: ClaimTarget, holder: PeerIdentity, token: ClaimToken) {
        reservations.update { it + ((target to token) to Reservation.GivingBack) }
        claims.releaseUnreceived(ClaimsConnection.Unreceived(target, holder, token))
    }

    /** Takes [token] on [target] into a wait's hands; false if another wait has it, or it was handed over. */
    internal fun reserve(target: ClaimTarget, token: ClaimToken): Boolean {
        val key = target to token
        return reservations.getAndUpdate { if (key in it) it else it + (key to Reservation.InHand) }[key] == null
    }

    /** Records that [token] on [target] reached a caller. */
    internal fun handedOver(target: ClaimTarget, token: ClaimToken) {
        reservations.update { it + ((target to token) to Reservation.HandedOver) }
    }

    /** Suspends until [token] on [target] leaves a wait's hands: handed over, or given back. */
    internal suspend fun awaitOutOfHand(target: ClaimTarget, token: ClaimToken) {
        reservations.first { it[target to token] != Reservation.InHand }
    }

    /**
     * Gives back this bridge's own claim of [target], held as [holder] under [token], unless a
     * caller received it or another wait has it in hand. One update reads its reservation and
     * marks it as being given back, so no wait takes it into its hands after; then it's released.
     * The caller claims the record again under a fresh token, and any release of [token] still on
     * its way to the voter is rejected.
     */
    internal suspend fun giveBack(target: ClaimTarget, holder: PeerIdentity, token: ClaimToken): OwnClaim {
        val key = target to token
        val found = reservations.getAndUpdate { if (it[key].givable()) it + (key to Reservation.GivingBack) else it }[key]
        return when (found) {
            Reservation.InHand -> OwnClaim.InHand
            Reservation.HandedOver -> OwnClaim.HandedOver
            null, Reservation.GivingBack ->
                if (claims.releaseUnreceived(ClaimsConnection.Unreceived(target, holder, token))) OwnClaim.GivenBack else OwnClaim.Queued
        }
    }

    private fun Reservation?.givable() = this == null || this == Reservation.GivingBack

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
internal enum class OwnClaim {
    /** A caller received it: the record is passed over. */
    HandedOver,

    /** Another wait has it in hand: wait that out. */
    InHand,

    /** Released, or no longer held under its token: claim the record again. */
    GivenBack,

    /** Its release didn't commit now and is queued until it does: back off, then claim again. */
    Queued,
}

/** A write the bridge refused, with a message for the agent that names why. */
open class BridgeRefusal(message: String) : Exception(message)

/** A claim operation refused because no claims link is up: [CLAIMS_OFFLINE]. */
class ClaimsOfflineRefusal : BridgeRefusal(CLAIMS_OFFLINE)

/** What a claim operation answers while no claims link is up. */
const val CLAIMS_OFFLINE: String =
    "Claims offline: the bridge can't reach the host's claims voter and is reconnecting. Nothing was claimed; try again shortly."
