package us.tractat.bee.core

import kotlinx.serialization.Serializable
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.VersionVector

/**
 * What an actor had seen when they acted: every appended entry their replica had delivered, in the
 * surface and in the board's records (§6.2, kuilt#2869's input-history contract).
 *
 * Only appended `Rga` entries can be named (A1). Each array's `Rga` mints its own sequence
 * numbers, so two arrays can hand out the same dot: the surface maps are keyed by each append-only
 * array's JSON Pointer into the data model, and record dots are held apart from them.
 *
 * A dot was seen if it's in the dots **or** at or below the floor, for its array. Capture folds
 * every gap-free run into the floor and keeps only the dots above it, so a basis stays
 * O(authors + gaps) rather than growing with every record on the board.
 */
@Serializable
data class Basis(
    val surfaceDots: Map<String, Set<Dot>>,
    val surfaceFloors: Map<String, VersionVector>,
    val recordDots: Set<Dot>,
    val recordFloor: VersionVector,
) {
    /**
     * Whether the actor had seen entry [dot] of the append-only array at [arrayPointer].
     *
     * The two-armed delivery rule (`causalDots()` ∪ `causalFloor()`), written once here. It's the
     * `Quilted.delivered(dot)` that kuilt#2869 is waiting for a second consumer of; see the bee
     * primitives tracking issue, tractat-us/kuilt#3119.
     */
    fun saw(arrayPointer: String, dot: Dot): Boolean =
        seen(surfaceDots[arrayPointer].orEmpty(), surfaceFloors[arrayPointer] ?: VersionVector.EMPTY, dot)

    /** Whether the actor had seen the record [dot]. The same two-armed rule as [saw]. */
    fun sawRecord(dot: Dot): Boolean = seen(recordDots, recordFloor, dot)

    /**
     * Whether [state], a handler's replica, has delivered everything this basis names for
     * [surfaceId]. When it hasn't, the basis is unknown to that handler, never applicable. A
     * replica that doesn't hold the surface yet hasn't delivered it, so the answer is `false`.
     */
    fun completeIn(state: BoardState, surfaceId: String): Boolean {
        if (state.surfaceDoc(surfaceId) == null) return false
        val held = capture(state, surfaceId)
        val pointers = surfaceDots.keys + surfaceFloors.keys
        return pointers.all { p ->
            val heldDots = held.surfaceDots[p].orEmpty()
            val heldFloor = held.surfaceFloors[p] ?: VersionVector.EMPTY
            surfaceDots[p].orEmpty().all { seen(heldDots, heldFloor, it) } &&
                covers(surfaceFloors[p] ?: VersionVector.EMPTY, heldDots, heldFloor)
        } && recordDots.all(held::sawRecord) && covers(recordFloor, held.recordDots, held.recordFloor)
    }

    companion object {
        /**
         * The basis for an action on [surfaceId], read from [state] as it is now. Call it inside the
         * transform that appends the record, so it reads the very state the record joins.
         *
         * It walks each `JsonNode.Array` in the data model and reads that array's own `Rga`. It
         * never uses `JsonCrdt.causalDots()`, which unions every array's dots and loses which array
         * a dot belongs to.
         */
        fun capture(state: BoardState, surfaceId: String): Basis {
            val doc = requireNotNull(state.surfaceDoc(surfaceId)) { "No surface $surfaceId on the board." }
            val arrays = buildMap { collectArrays(doc[SurfaceFormat.DATA], "", this) }
            val compact = arrays.mapValues { (_, crdt) -> Compacted.of(crdt) }
            val records = Compacted.of(state.records)
            return Basis(
                surfaceDots = compact.mapValues { it.value.dots },
                surfaceFloors = compact.mapValues { it.value.floor },
                recordDots = records.dots,
                recordFloor = records.floor,
            )
        }

        private fun collectArrays(node: JsonNode?, pointer: String, into: MutableMap<String, Quilted<*>>) {
            when (node) {
                is JsonNode.Object -> node.map.keys.forEach { key ->
                    collectArrays(node.map[key], "$pointer/${SurfaceFormat.escapeSegment(key)}", into)
                }
                // Entries are leaves (A12), so an array holds no array to name.
                is JsonNode.Array -> into[pointer] = node.rga
                is JsonNode.Leaf, null -> Unit
            }
        }

        private fun seen(dots: Set<Dot>, floor: VersionVector, dot: Dot): Boolean = dot in dots || floor.contains(dot)

        /**
         * Whether a replica holding [heldDots] above [heldFloor] has seen every dot at or below
         * [floor]. Per author, the held floor answers up to its own height, and only the seqs above
         * it are looked up.
         */
        private fun covers(floor: VersionVector, heldDots: Set<Dot>, heldFloor: VersionVector): Boolean =
            floor.entries.all { (author, high) ->
                val from = heldFloor[author]
                high <= from || (from + 1..high).all { Dot(author, it) in heldDots }
            }
    }

    /** A delivered set as its gap-free floor plus the dots above it: the same set, smaller. */
    private class Compacted(val dots: Set<Dot>, val floor: VersionVector) {
        companion object {
            fun of(crdt: Quilted<*>): Compacted {
                val delivered = crdt.causalDots()
                val floor = VersionVector.contiguous(delivered, crdt.causalFloor())
                return Compacted(delivered.filterTo(mutableSetOf()) { !floor.contains(it) }, floor)
            }
        }
    }
}
