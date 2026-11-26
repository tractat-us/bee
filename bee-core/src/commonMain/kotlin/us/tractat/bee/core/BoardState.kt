package us.tractat.bee.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Instant

/**
 * A peer shown as attached, with what it's on and when it last acted (§6.4).
 *
 * [claimsNode] is the Raft node of the peer's newest claims link, or null before its first one
 * came up. The host departs an agent `Crashed` when the voter drops that node (bee#28).
 */
@Serializable
data class PresenceEntry(
    @Serializable(with = PeerIdentityAsString::class) val identity: PeerIdentity,
    val claimsNode: NodeId?,
    val status: String?,
    val lastActedAt: Instant,
    val waiting: Boolean,
)

/** A committed `Depart`: [identity] is retired, for [reason], at log index [logIndex] (A3). */
@Serializable
data class Departure(
    @Serializable(with = PeerIdentityAsString::class) val identity: PeerIdentity,
    val reason: DepartReason,
    val logIndex: Long,
)

/** The host's mirror of one claim target's committed state (A2). */
@Serializable
data class ClaimView(val status: String, val holder: String?, val token: String?, val logIndex: Long) {
    companion object {
        const val FREE = "free"
        const val HELD = "held"
        const val ORPHANED = "orphaned"
        const val DONE = "done"

        /** [status] as mirrored at log index [logIndex]; a token reads `<term>:<index>`. */
        fun of(status: ClaimStatus, logIndex: Long): ClaimView = when (status) {
            ClaimStatus.Free -> ClaimView(FREE, null, null, logIndex)
            is ClaimStatus.Held -> ClaimView(HELD, status.holder.toString(), status.token.text, logIndex)
            is ClaimStatus.Orphaned -> ClaimView(ORPHANED, status.holder.toString(), status.token.text, logIndex)
            is ClaimStatus.Done -> ClaimView(DONE, status.by.toString(), null, logIndex)
        }
    }
}

/**
 * The board: every peer's replica of it is one of these (§6).
 *
 * A join-semilattice taken componentwise over five lattices:
 * - [surfaces], one `JsonCrdt` per surface id. Surfaces close, and only the host removes one,
 *   once it has been closed for a day ([compactClosed], bee#274). A removed id stays removed: its
 *   tombstone stays under it, no write to it is accepted, and no read shows it ([isRemoved]).
 * - [records], the action records, appended and never edited (§6.2).
 * - [presence], who is attached.
 * - [departed] and [claims], which only the host writes (A2).
 *
 * Every mutator returns the delta for `quilter.mutate { … }`. A surface document read out of
 * [surfaces] carries whichever author it was last joined under, so a mutator re-homes it to the
 * writing replica before writing; nothing here keeps a replica of its own.
 */
class BoardState internal constructor(
    val surfaces: ORMap<String, JsonCrdt>,
    val records: Rga<ActionRecord>,
    val presence: EphemeralMap<PresenceEntry>,
    val departed: GSet<Departure>,
    val claims: LWWMap<String, ClaimView>,
) : Quilted<BoardState> {

    override fun piece(other: BoardState): BoardState = BoardState(
        surfaces = surfaces.piece(other.surfaces),
        records = records.piece(other.records),
        presence = presence.piece(other.presence),
        departed = departed.piece(other.departed),
        claims = claims.piece(other.claims),
    )

    /** The records' dots only (kuilt#2177): surface arrays aren't compacted through the board. */
    override fun causalDots(): Set<Dot> = records.causalDots()

    override fun causalFloor(): VersionVector = records.causalFloor()

    /**
     * This state, for [replica] to mutate. Every mutator already names its replica and re-homes
     * what it reads, so the state itself carries none and comes back unchanged. It's here so a
     * deserialized board follows kuilt's `withReplica` idiom.
     */
    @Suppress("UNUSED_PARAMETER")
    fun withReplica(replica: ReplicaId): BoardState = this

    // ---- Mutators ----

    /**
     * Opens a new surface [id]. It must not exist yet: a closed surface is reopened, not opened, and
     * a removed one is never opened again.
     *
     * @throws SurfaceRemoved if the host removed [id]: the surface needs a new id
     */
    fun openSurface(
        replica: ReplicaId,
        id: String,
        meta: SurfaceMeta,
        components: List<JsonObject>,
        data: JsonObject,
    ): Patch<BoardState> {
        requireReplica(replica)
        if (isRemoved(id)) throw SurfaceRemoved(id)
        require(surfaces[id] == null) { "Surface $id already exists; reopen it instead." }
        return surfacePatch(replica, id, SurfaceDoc.open(replica, meta, components, data))
    }

    /** Upserts [components] by their `id`. Components it doesn't name are left alone. */
    fun updateComponents(replica: ReplicaId, id: String, components: List<JsonObject>): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.updateComponents(it, replica, components) }

    /** Retitles surface [id]. Its id, its place on the board and its content stay. */
    fun retitleSurface(replica: ReplicaId, id: String, title: String): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.setTitle(it, replica, title) }

    /**
     * Closes surface [id] at [at]. It keeps its content and its records ("clear" never deletes), until
     * the host compacts it ([compactClosed]).
     */
    fun closeSurface(replica: ReplicaId, id: String, at: Instant): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.setClosedAt(it, replica, at) }

    /**
     * Reopens surface [id] with its content and records. A close and a reopen that race resolve to
     * closed, on every peer: the projection's order puts a `closedAt` time above JSON null.
     */
    fun reopenSurface(replica: ReplicaId, id: String): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.setClosedAt(it, replica, null) }

    /**
     * Writes [value] at JSON Pointer [path] in surface [id]'s data model. Objects are written as
     * keyed objects, scalars as leaves from the register just read (A13), and a JSON array whole,
     * as one leaf.
     *
     * @throws IllegalArgumentException if the write would change the path's type
     */
    fun updateData(replica: ReplicaId, id: String, path: String, value: JsonElement): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.updateData(it, replica, path, value) }

    /** Appends [value] to the append-only array at [path] in surface [id] (A12). */
    fun appendEntry(replica: ReplicaId, id: String, path: String, value: JsonElement): Patch<BoardState> =
        writeSurface(replica, id) { SurfaceDoc.appendEntry(it, replica, path, value) }

    fun appendRecord(replica: ReplicaId, record: ActionRecord): Patch<BoardState> {
        requireReplica(replica)
        if (isRemoved(record.surfaceId)) throw SurfaceRemoved(record.surfaceId)
        require(surfaces[record.surfaceId] != null) { "No surface ${record.surfaceId} on the board." }
        val (_, op) = records.insertAt(replica, records.size, record)
        return Patch(EMPTY.copy(records = Rga.empty<ActionRecord>().apply(op)))
    }

    /**
     * Posts [entry] as [replica]'s presence. A replica has one entry, and the post with the
     * greater [clock] wins, so a poster passes a clock that only grows (§6.4).
     */
    fun postPresence(replica: ReplicaId, entry: PresenceEntry, clock: Long): Patch<BoardState> {
        requireReplica(replica)
        return Patch(EMPTY.copy(presence = EphemeralMap.empty<PresenceEntry>().put(replica, entry, clock)))
    }

    /**
     * Mirrors the committed claim [table] onto the board: every target whose view changed, and every
     * identity newly retired (A2). Only the host calls it, from the voter's fold.
     *
     * Each view is written at the table's applied index, a time that only grows across host boots
     * because the log is durable, so a later mirror always wins. A target the table no longer holds
     * (released) shows `free`. A newly retired identity is recorded at the table's index, which is
     * its `Depart`'s own index when the host mirrors every committed table in order.
     *
     * A removed record's claim is never mirrored again ([compactClosed]): the table still holds it,
     * and a mirror of it would undo the tombstone.
     *
     * The "only grows across host boots" premise can break when a tab is left open across a wipe, or
     * `claims/` is moved aside: the tab's old views carry indexes the new log hasn't reached (bee#46).
     */
    fun mirrorClaims(replica: ReplicaId, table: ClaimTable): Patch<BoardState> {
        requireReplica(replica)
        val index = table.appliedIndex
        val removed = removedRecordTargets()
        val views = table.targets.entries.filter { (target, _) -> target.value !in removed }
            .associate { (target, status) -> target.value to ClaimView.of(status, index) }
        val freed = claims.entries.filter { (key, view) -> key !in views && key !in removed && view.status != ClaimView.FREE }
            .mapValues { ClaimView(ClaimView.FREE, holder = null, token = null, logIndex = index) }
        val changed = (views + freed).filter { (key, view) -> claims[key]?.copy(logIndex = view.logIndex) != view }
        val claimsDelta = changed.entries.fold(LWWMap.empty<String, ClaimView>()) { delta, (key, view) ->
            delta.piece(claims.set(replica, index, key, view).delta)
        }
        val known = departed.elements.map { it.identity }.toSet()
        val departedDelta = table.retiredIdentities.filterKeys { it !in known }.entries
            .fold(GSet.empty<Departure>()) { delta, (identity, reason) ->
                delta.piece(departed.add(Departure(identity, reason, index)).delta)
            }
        return Patch(EMPTY.copy(claims = claimsDelta, departed = departedDelta))
    }

    /**
     * Removes for good each surface of [ids] that is closed at or before [closedBy], with its action
     * records and their claims' mirror, and leaves its tombstone, stamped [at], under its id
     * (bee#274; Iain, 2026-11-23: a removed id stays removed). Only the host calls it, from [ids] it
     * has seen closed for its grace period; checking the close again here, in the host's `mutate`,
     * means a reopen the host has already merged keeps the surface.
     *
     * The removal is causal: it retires the surface's tags this replica has seen, so an older copy
     * on a lagging replica, holding only tags seen here, can't bring the content back on merge. A
     * write this replica hasn't seen keeps its tag (add-wins), and that tag carries everything its
     * writer ever contributed to the surface, its opener's meta or its own close included. It merges
     * under the tombstone, which no such write can retire ([SurfaceDoc.tombstone]), so it shows
     * nowhere and the id still can't be opened: the late write's content stays on the board, hidden.
     *
     * A record's tombstone keeps its value until the records are compacted, so removing records hides
     * them rather than shrinking the board. A claim's mirror is tombstoned one past its own index,
     * and [mirrorClaims] never writes a removed record's claim again.
     */
    fun compactClosed(replica: ReplicaId, ids: Set<String>, closedBy: Instant, at: Instant): Patch<BoardState> {
        requireReplica(replica)
        val gone = closedBy(closedBy) intersect ids
        // Each id's removal and tombstone are made against the map after the ones before it, so each
        // tombstone gets a tag of its own: two minted from the same map would share one, and the
        // removal of the next id would retire it.
        val (_, surfacesDelta) = gone.fold(surfaces to ORMap.empty<String, JsonCrdt>()) { (map, delta), id ->
            val removal = map.remove(id).delta
            val removed = map.piece(removal)
            // Put after the removal, so the tombstone's tag carries none of this replica's own
            // earlier contributions to the surface.
            val tombstone = removed.put(replica, id, SurfaceDoc.tombstone(replica, at)).delta
            removed.piece(tombstone) to delta.piece(removal).piece(tombstone)
        }
        val removed = records.entries().filter { (_, record) -> record.surfaceId in gone }.map { it.first }
        val recordsDelta = removed.fold(Rga.empty<ActionRecord>()) { delta, id -> delta.apply(RgaOp.Remove(id)) }
        val claimsDelta = removed.map { RecordId(it.dot).claimTarget.value }
            .fold(LWWMap.empty<String, ClaimView>()) { delta, key ->
                claims[key]?.let { view -> delta.piece(claims.remove(replica, view.logIndex + 1, key).delta) } ?: delta
            }
        return Patch(EMPTY.copy(surfaces = surfacesDelta, records = recordsDelta, claims = claimsDelta))
    }

    // ---- Reads ----

    /** Open surface ids, in the order they were opened (ties by id). */
    fun openSurfaces(): List<String> = surfaceMetas()
        .filter { it.second.closedAt == null }
        .sortedWith(compareBy<Pair<String, SurfaceMeta>> { it.second.openedAt }.thenBy { it.first })
        .map { it.first }

    /** Closed surface ids, newest first (ties by id). */
    fun closedSurfaces(): List<String> = surfaceMetas()
        .filter { it.second.closedAt != null }
        .sortedWith(compareByDescending<Pair<String, SurfaceMeta>> { it.second.closedAt }.thenBy { it.first })
        .map { it.first }

    /** The surfaces closed at or before [instant], by their closers' clocks. */
    fun closedBy(instant: Instant): Set<String> =
        surfaceMetas().filter { (_, meta) -> meta.closedAt?.let { it <= instant } == true }.map { it.first }.toSet()

    /** Surface [id]'s document, or null if there's none or the host removed it ([isRemoved]). */
    fun surfaceDoc(id: String): JsonCrdt? = surfaces[id]?.takeUnless { SurfaceDoc.removedAt(it) != null }

    /** Whether surface [id] shows open: its open has arrived and it isn't closed. */
    fun isOpen(id: String): Boolean = meta(id)?.let { it.closedAt == null } == true

    fun meta(id: String): SurfaceMeta? = surfaceDoc(id)?.let(SurfaceDoc::meta)

    /**
     * Whether the host removed surface [id] for good ([compactClosed]): it can't be opened, reopened
     * or written again, and nothing from it shows. Something that wants that surface back opens a
     * new id.
     */
    fun isRemoved(id: String): Boolean = surfaces[id]?.let(SurfaceDoc::removedAt) != null

    /**
     * Every record on the board, whatever its surface, in sequence order. A record on a removed
     * surface that arrived after the removal is left out, with the rest of that surface.
     */
    fun records(): List<Pair<RecordId, ActionRecord>> {
        val removed = surfaces.keys.filterTo(mutableSetOf()) { isRemoved(it) }
        return records.entries().filter { it.second.surfaceId !in removed }.map { (id, record) -> RecordId(id.dot) to record }
    }

    /** Surface [surfaceId]'s records, in sequence order. */
    fun records(surfaceId: String): List<Pair<RecordId, ActionRecord>> = records().filter { it.second.surfaceId == surfaceId }

    /** The mirrored view of claim [target], or null if the host never mirrored it. */
    fun claimView(target: ClaimTarget): ClaimView? = claims[target.value]

    /** Every committed `Depart` the host has mirrored. */
    fun departures(): Set<Departure> = departed.elements

    /**
     * Each poster's newest presence entry, however old: a dead peer's last entry stays here. Who
     * is attached now is [LivePresence]'s view, which ages entries out.
     */
    fun latestPresence(): List<PresenceEntry> = presence.entries.values.mapNotNull { it.value }

    /**
     * This board without its presence, the form it is saved in (Task 9). Who is attached is true
     * only while they are: a restored board starts with nobody, and each peer posts itself again.
     */
    fun withoutPresence(): BoardState = copy(presence = EphemeralMap.empty())

    /**
     * What [other] adds to this board, as a patch, or null when it adds nothing: each of
     * [other]'s components that would move this board's, and the empty one for each that would
     * not. The board's join is taken component by component, so absorbing the patch lands where
     * absorbing [other] does: `piece(addedBy(other)) == piece(other)`.
     *
     * It is no finer than a component. A surface edit brings every surface in [other], since an
     * `ORMap` can't be cut down to the keys that moved from outside kuilt (tractat-us/kuilt#3632).
     */
    fun addedBy(other: BoardState): Patch<BoardState>? {
        val added = BoardState(
            surfaces = surfaces.ifMoves(other.surfaces, EMPTY.surfaces),
            records = records.ifMoves(other.records, EMPTY.records),
            presence = presence.ifMoves(other.presence, EMPTY.presence),
            departed = departed.ifMoves(other.departed, EMPTY.departed),
            claims = claims.ifMoves(other.claims, EMPTY.claims),
        )
        return Patch(added).takeIf { added != EMPTY }
    }

    /** [other] if it would move this component, else [empty]. */
    private fun <S : Quilted<S>> S.ifMoves(other: S, empty: S): S = if (piece(other) != this) other else empty

    /**
     * Every surface whose open has arrived, with its meta. One still waiting for its open is left
     * out: a write can arrive before its surface's open ([SurfaceDoc.meta], bee#171).
     */
    private fun surfaceMetas(): List<Pair<String, SurfaceMeta>> =
        surfaces.keys.mapNotNull { id -> meta(id)?.let { id to it } }

    /**
     * The claim targets of every record removed from the board, tombstoned or compacted away. A record
     * that hasn't arrived yet isn't one: its claim may reach the mirror first.
     */
    private fun removedRecordTargets(): Set<String> =
        (records.tombstones + records.compactedIds).map { RecordId(it.dot).claimTarget.value }.toSet()

    private fun writeSurface(replica: ReplicaId, id: String, write: (JsonCrdt) -> Patch<JsonCrdt>): Patch<BoardState> {
        requireReplica(replica)
        if (isRemoved(id)) throw SurfaceRemoved(id)
        val doc = requireNotNull(surfaces[id]) { "No surface $id on the board." }.withReplica(replica)
        return surfacePatch(replica, id, write(doc).delta)
    }

    // ReplicaId("") is Rga's HEAD sentinel and a deserialized doc's placeholder; minting under it
    // would collide with both.
    private fun requireReplica(replica: ReplicaId) =
        require(replica.value.isNotEmpty()) { "A board write needs a real replica, not ReplicaId(\"\")." }

    private fun surfacePatch(replica: ReplicaId, id: String, docDelta: JsonCrdt): Patch<BoardState> =
        Patch(EMPTY.copy(surfaces = surfaces.put(replica, id, docDelta).delta))

    internal fun copy(
        surfaces: ORMap<String, JsonCrdt> = this.surfaces,
        records: Rga<ActionRecord> = this.records,
        presence: EphemeralMap<PresenceEntry> = this.presence,
        departed: GSet<Departure> = this.departed,
        claims: LWWMap<String, ClaimView> = this.claims,
    ) = BoardState(surfaces, records, presence, departed, claims)

    override fun equals(other: Any?): Boolean = other is BoardState &&
        surfaces == other.surfaces &&
        records == other.records &&
        presence == other.presence &&
        departed == other.departed &&
        claims == other.claims

    override fun hashCode(): Int = listOf(surfaces, records, presence, departed, claims).hashCode()

    override fun toString(): String = "BoardState(surfaces=${surfaces.keys}, records=${records.size})"

    companion object {
        val EMPTY: BoardState = BoardState(
            surfaces = ORMap.empty(),
            records = Rga.empty(),
            presence = EphemeralMap.empty(),
            departed = GSet.empty(),
            claims = LWWMap.empty(),
        )

        /**
         * The board's wire form. Surface documents come back under `ReplicaId("")`, which no
         * mutator writes with: each re-homes the document it reads to its own replica.
         */
        fun serializer(): KSerializer<BoardState> = BoardStateSerializer
    }
}

/**
 * A write or an open named surface [id], which the host removed for good (bee#274). The surface is
 * gone: whatever wants it back opens it under a new id.
 */
class SurfaceRemoved(val id: String) :
    IllegalArgumentException("Surface $id was removed from the board for good and can't be opened or written again; use a new surfaceId.")

@OptIn(ExperimentalSerializationApi::class)
private object BoardStateSerializer : KSerializer<BoardState> {
    private val surfaces by lazy { ORMap.serializer(String.serializer(), JsonCrdt.serializer()) }
    private val records by lazy { Rga.wireSerializer(ActionRecord.serializer()) }
    private val presence by lazy { EphemeralMap.serializer(PresenceEntry.serializer()) }
    private val departed by lazy { GSet.serializer(Departure.serializer()) }
    private val claims by lazy { LWWMap.serializer(String.serializer(), ClaimView.serializer()) }

    // JSON round-trips in BoardStateTest.serializedStateRoundTripsAfterWithReplica, and CBOR, the
    // Quilter's wire, in BoardStateTest.populatedBoardRoundTripsThroughTheQuilterWire.
    override val descriptor: SerialDescriptor by lazy {
        buildClassSerialDescriptor("us.tractat.bee.core.BoardState") {
            element("surfaces", surfaces.descriptor)
            element("records", records.descriptor)
            element("presence", presence.descriptor)
            element("departed", departed.descriptor)
            element("claims", claims.descriptor)
        }
    }

    override fun serialize(encoder: Encoder, value: BoardState) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, surfaces, value.surfaces)
        encodeSerializableElement(descriptor, 1, records, value.records)
        encodeSerializableElement(descriptor, 2, presence, value.presence)
        encodeSerializableElement(descriptor, 3, departed, value.departed)
        encodeSerializableElement(descriptor, 4, claims, value.claims)
    }

    override fun deserialize(decoder: Decoder): BoardState = decoder.decodeStructure(descriptor) {
        var state = BoardState.EMPTY
        while (true) {
            state = when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> state.copy(surfaces = decodeSerializableElement(descriptor, 0, surfaces))
                1 -> state.copy(records = decodeSerializableElement(descriptor, 1, records))
                2 -> state.copy(presence = decodeSerializableElement(descriptor, 2, presence))
                3 -> state.copy(departed = decodeSerializableElement(descriptor, 3, departed))
                4 -> state.copy(claims = decodeSerializableElement(descriptor, 4, claims))
                else -> throw SerializationException("Unexpected index $index in BoardState")
            }
        }
        state
    }

}
