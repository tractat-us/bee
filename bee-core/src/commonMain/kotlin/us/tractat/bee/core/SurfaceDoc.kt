package us.tractat.bee.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.piece
import kotlin.time.Instant

/** What the board knows about a surface besides its content. A closed surface has [closedAt]. */
data class SurfaceMeta(
    val title: String,
    val catalogId: String,
    val openedBy: PeerIdentity,
    val openedAt: Instant,
    val closedAt: Instant? = null,
)

/**
 * Writes and reads one surface's `JsonCrdt` document, keeping its two kinds of state apart (§6.1).
 *
 * Every leaf write starts from the register just read, never from an empty one, so a rewrite
 * supersedes what the writer saw and leaves no phantom conflict (A13). Appended entries go into a
 * `JsonNode.Array` and are never edited (A12). A path never changes kind, and no node is ever
 * removed: an object write replaces by clearing what its writer saw (#14; see [updateData]).
 *
 * Each write returns the document delta, for [BoardState] to put under the surface id.
 */
internal object SurfaceDoc {
    fun open(
        replica: ReplicaId,
        meta: SurfaceMeta,
        components: List<JsonObject>,
        data: JsonObject,
    ): JsonCrdt {
        val writes = listOf<(JsonCrdt) -> Patch<JsonCrdt>>(
            { it.set(SurfaceFormat.META, metaNode(replica, meta)) },
            { updateComponents(it, replica, components) },
            { updateData(it, replica, "", data) },
        )
        return threaded(JsonCrdt.empty(replica), writes)
    }

    fun updateComponents(doc: JsonCrdt, replica: ReplicaId, components: List<JsonObject>): Patch<JsonCrdt> {
        val existing = doc[SurfaceFormat.COMPONENTS]
        val map = objectMap(existing, "/${SurfaceFormat.COMPONENTS}")
        val delta = threadedPuts(map, replica, components.map { componentId(it) to it }) { _, current, component ->
            leafFrom(current, replica, SurfaceFormat.encodeLeaf(component))
        }
        return doc.set(SurfaceFormat.COMPONENTS, JsonNode.Object(delta))
    }

    fun setClosedAt(doc: JsonCrdt, replica: ReplicaId, at: Instant?): Patch<JsonCrdt> {
        val meta = doc[SurfaceFormat.META] as JsonNode.Object
        val value = at?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null
        val leaf = leafFrom(meta.map[SurfaceFormat.META_CLOSED_AT], replica, value)
        return doc.set(SurfaceFormat.META, JsonNode.Object(meta.map.put(replica, SurfaceFormat.META_CLOSED_AT, leaf).delta))
    }

    /**
     * Writes [value] at [pointer] in the data model (`""` or `"/"` is the root): an object as a
     * keyed object, each key written the same way, and a scalar or a whole array as one leaf.
     *
     * An object write replaces the value at its path, as A2UI's `updateDataModel` does
     * (tractat-us/bee#14), by **keep-and-clear**: every key this replica holds there that [value]
     * doesn't name is kept and cleared, at every depth.
     * - A leaf gets [SurfaceFormat.CLEARED], written from the register just read (A13), so it
     *   supersedes exactly the values this replica saw. The projection hides a cleared value.
     * - An append-only array has every entry this replica saw tombstoned with `Rga` removes.
     * - An object is cleared the same way, member by member.
     *
     * bee never removes a key: `ORMap.remove` drops the removed subtree's causal history in every
     * dot space, so a key written again would reissue its register dots, map tags and `Rga` dots
     * (tractat-us/kuilt#3126). Keeping every node keeps every counter, so no replica mints a dot
     * twice: a later write supersedes only what it saw, a concurrent write survives as a conflict,
     * and a basis that saw an old entry can't claim a new one. A concurrent write, or append,
     * survives the clear. Under a cleared object it shows alone, so a card can show as a
     * fragment, such as one holding `column` and no `label`. The cost is that cleared nodes and
     * tombstones accumulate: nothing is ever collected. `ReplaceSemanticsTest` pins all of this.
     *
     * A path keeps its kind for good, cleared or not: an object takes only objects, an
     * append-only array only appends, and a leaf only scalars and whole arrays. A cleared leaf
     * takes either of those, since both are leaves. So a clear never opens a path to the
     * `Object > Array > Leaf` race, which would drop a peer's concurrent write with no conflict.
     *
     * @throws IllegalArgumentException if a node on the way isn't an object, a key is empty, or the
     *   write would change a path's kind (object, append-only array or leaf), or would put a whole
     *   array on a leaf holding a scalar, or a scalar on one holding a whole array
     */
    fun updateData(doc: JsonCrdt, replica: ReplicaId, pointer: String, value: JsonElement): Patch<JsonCrdt> {
        val segments = SurfaceFormat.segments(pointer)
        require(segments.firstOrNull() != SurfaceFormat.RESERVED_DATA_KEY) { "/${SurfaceFormat.RESERVED_DATA_KEY} is reserved." }
        require(segments.isNotEmpty() || value is JsonObject) { "The data model's root must be an object." }
        if (segments.isEmpty()) {
            require(SurfaceFormat.RESERVED_DATA_KEY !in (value as JsonObject)) { "/${SurfaceFormat.RESERVED_DATA_KEY} is reserved." }
        }
        val delta = writeAt(doc[SurfaceFormat.DATA], segments, 0, replica) { existing, at ->
            writeValue(existing, replica, value, at)
        }
        return doc.set(SurfaceFormat.DATA, delta)
    }

    /**
     * Appends [value] as a new entry of the append-only array at [pointer], creating the array if
     * the path is empty. The entry is one leaf, never edited after.
     *
     * @throws IllegalArgumentException if the path holds anything but an append-only array
     */
    fun appendEntry(doc: JsonCrdt, replica: ReplicaId, pointer: String, value: JsonElement): Patch<JsonCrdt> {
        val segments = SurfaceFormat.segments(pointer)
        require(segments.isNotEmpty()) { "An append-only array can't be the data model's root." }
        require(segments.first() != SurfaceFormat.RESERVED_DATA_KEY) { "/${SurfaceFormat.RESERVED_DATA_KEY} is reserved." }
        val delta = writeAt(doc[SurfaceFormat.DATA], segments, 0, replica) { existing, at ->
            val rga = when (existing) {
                null -> Rga.empty()
                is JsonNode.Array -> existing.rga
                else -> refuse(at, existing, "an append-only array")
            }
            val entry = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, SurfaceFormat.encodeLeaf(value)))
            val (_, op) = rga.insertAt(replica, rga.size, entry)
            JsonNode.Array(Rga.empty<JsonNode>().apply(op))
        }
        return doc.set(SurfaceFormat.DATA, delta)
    }

    fun meta(doc: JsonCrdt): SurfaceMeta {
        val meta = doc[SurfaceFormat.META] as JsonNode.Object
        // Meta is read raw, never through decodeLeaf: a title may start with any characters.
        fun field(key: String): String? = SurfaceFormat.metaString(meta.map[key] as JsonNode.Leaf)
        fun required(key: String): String = requireNotNull(field(key)) { "Surface meta has no $key." }
        return SurfaceMeta(
            title = required(SurfaceFormat.META_TITLE),
            catalogId = required(SurfaceFormat.META_CATALOG_ID),
            openedBy = PeerIdentity.parse(required(SurfaceFormat.META_OPENED_BY)),
            openedAt = Instant.parse(required(SurfaceFormat.META_OPENED_AT)),
            closedAt = field(SurfaceFormat.META_CLOSED_AT)?.let(Instant::parse),
        )
    }

    private fun metaNode(replica: ReplicaId, meta: SurfaceMeta): JsonNode {
        val fields = listOf(
            SurfaceFormat.META_TITLE to JsonValue.Str(meta.title),
            SurfaceFormat.META_CATALOG_ID to JsonValue.Str(meta.catalogId),
            SurfaceFormat.META_OPENED_BY to JsonValue.Str(meta.openedBy.toString()),
            SurfaceFormat.META_OPENED_AT to JsonValue.Str(meta.openedAt.toString()),
            SurfaceFormat.META_CLOSED_AT to (meta.closedAt?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        )
        return JsonNode.Object(threadedPuts(ORMap.empty(), replica, fields) { _, current, v -> leafFrom(current, replica, v) })
    }

    private fun componentId(component: JsonObject): String {
        val id = component["id"] as? JsonPrimitive
        require(id != null && id.isString && id.content.isNotEmpty()) { "A component has no string id: $component" }
        return id.content
    }

    /**
     * The delta for writing at [segments] below [node], from [depth] on: each object on the way is
     * rebuilt as one put (kuilt#2469), and [write] makes the node at the end of the path.
     */
    private fun writeAt(
        node: JsonNode?,
        segments: List<String>,
        depth: Int,
        replica: ReplicaId,
        write: (existing: JsonNode?, pointer: String) -> JsonNode,
    ): JsonNode {
        val at = SurfaceFormat.pointer(segments.take(depth))
        if (depth == segments.size) return write(node, at)
        val map = objectMap(node, at)
        val key = segments[depth]
        val child = writeAt(map[key], segments, depth + 1, replica, write)
        return JsonNode.Object(map.put(replica, key, child).delta)
    }

    private fun writeValue(existing: JsonNode?, replica: ReplicaId, value: JsonElement, at: String): JsonNode =
        when (value) {
            is JsonObject -> {
                val entries = value.entries.map { it.key to it.value }
                require(entries.none { it.first.isEmpty() }) { "$at holds an object with an empty key." }
                JsonNode.Object(replaceMembers(objectMap(existing, at), replica, entries, at))
            }
            else -> {
                val structured = value is JsonArray
                when (existing) {
                    null -> Unit
                    is JsonNode.Leaf -> {
                        // A cleared leaf takes a scalar or a whole array: both are leaves.
                        val held = existing.register.values.filterNot(SurfaceFormat::isCleared).map(SurfaceFormat::isStructured).toSet()
                        if (held.isNotEmpty() && structured !in held) refuse(at, existing, kindOf(value))
                    }
                    else -> refuse(at, existing, kindOf(value))
                }
                leafFrom(existing, replica, SurfaceFormat.encodeLeaf(value))
            }
        }

    /** A13: a leaf write starts from the register just read, so it supersedes what was seen. */
    private fun leafFrom(existing: JsonNode?, replica: ReplicaId, value: JsonValue): JsonNode.Leaf {
        val register = (existing as? JsonNode.Leaf)?.register ?: MVRegister.empty()
        return JsonNode.Leaf(register.set(replica, value))
    }

    private fun objectMap(node: JsonNode?, at: String): ORMap<String, JsonNode> = when (node) {
        null -> ORMap.empty()
        is JsonNode.Object -> node.map
        else -> refuse(at.ifEmpty { "/" }, node, "an object")
    }

    /**
     * Puts each of [entries] into [map] one after another, so each put mints its own tag, and
     * returns the joined deltas. Putting them all against the same [map] would mint one dot for
     * several writes.
     */
    private fun <V> threadedPuts(
        map: ORMap<String, JsonNode>,
        replica: ReplicaId,
        entries: List<Pair<String, V>>,
        node: (key: String, current: JsonNode?, value: V) -> JsonNode,
    ): ORMap<String, JsonNode> {
        var current = map
        var delta = ORMap.empty<String, JsonNode>()
        for ((key, value) in entries) {
            val patch = current.put(replica, key, node(key, current[key], value))
            current = current.piece(patch)
            delta = delta.piece(patch.delta)
        }
        return delta
    }

    /**
     * The delta that leaves the object [map], at [at], holding [entries] and nothing else this
     * replica has seen: each entry written, and each other key cleared as [updateData] describes.
     */
    private fun replaceMembers(
        map: ORMap<String, JsonNode>,
        replica: ReplicaId,
        entries: List<Pair<String, JsonElement>>,
        at: String,
    ): ORMap<String, JsonNode> {
        val named = entries.map { it.first }.toSet()
        // A key already clear is skipped: clearing it again would only mint a tag.
        val dropped = map.keys.filterNot { it in named || isClear(map[it]!!) }.sorted()
        val writes = entries.map { (key, v) -> key to { current: JsonNode? -> writeValue(current, replica, v, child(at, key)) } } +
            dropped.map { key -> key to { current: JsonNode? -> clear(current!!, replica, child(at, key)) } }
        return threadedPuts(map, replica, writes) { _, current, write -> write(current) }
    }

    /** The delta clearing [node], keeping it and its causal history: see [updateData]. */
    private fun clear(node: JsonNode, replica: ReplicaId, at: String): JsonNode = when (node) {
        is JsonNode.Leaf -> leafFrom(node, replica, SurfaceFormat.CLEARED)
        is JsonNode.Array -> {
            val (_, removes) = node.rga.removeFirst(node.rga.size)
            JsonNode.Array(removes.fold(Rga.empty()) { delta, op -> delta.apply(op) })
        }
        is JsonNode.Object -> JsonNode.Object(replaceMembers(node.map, replica, emptyList(), at))
    }

    /** Whether [node] holds nothing this replica could clear. */
    private fun isClear(node: JsonNode): Boolean = when (node) {
        is JsonNode.Leaf -> node.register.values.all(SurfaceFormat::isCleared)
        is JsonNode.Array -> node.rga.size == 0
        is JsonNode.Object -> node.map.keys.all { isClear(node.map[it]!!) }
    }

    private fun child(at: String, key: String) = "$at/${SurfaceFormat.escapeSegment(key)}"

    private fun threaded(start: JsonCrdt, writes: List<(JsonCrdt) -> Patch<JsonCrdt>>): JsonCrdt {
        var current = start
        var delta: JsonCrdt? = null
        for (write in writes) {
            val patch = write(current)
            current = current.piece(patch)
            delta = delta?.piece(patch.delta) ?: patch.delta
        }
        return requireNotNull(delta)
    }

    private fun kindOf(value: JsonElement): String = when (value) {
        is JsonObject -> "an object"
        is JsonArray -> "a whole array"
        else -> "a scalar"
    }

    private fun refuse(at: String, existing: JsonNode, wanted: String): Nothing {
        val held = when (existing) {
            is JsonNode.Object -> "an object"
            is JsonNode.Array -> "an append-only array"
            is JsonNode.Leaf -> if (existing.register.values.any(SurfaceFormat::isStructured)) "a whole array" else "a scalar"
        }
        throw IllegalArgumentException("$at holds $held; writing $wanted there would change its type.")
    }
}
