package us.tractat.bee.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * `JsonNode.Array` and are never edited (A12). A path never changes type.
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
            { it.set(Format.META, metaNode(null, replica, meta)) },
            { updateComponents(it, replica, components) },
            { updateData(it, replica, "", data) },
        )
        return threaded(JsonCrdt.empty(replica), writes)
    }

    fun updateComponents(doc: JsonCrdt, replica: ReplicaId, components: List<JsonObject>): Patch<JsonCrdt> {
        val existing = doc[Format.COMPONENTS]
        val map = objectMap(existing, "/${Format.COMPONENTS}")
        val delta = threadedPuts(map, replica, components.map { componentId(it) to it }) { _, current, component ->
            leafFrom(current, replica, JsonValue.Str(Format.canonical(component)))
        }
        return doc.set(Format.COMPONENTS, JsonNode.Object(delta))
    }

    fun setClosedAt(doc: JsonCrdt, replica: ReplicaId, at: Instant?): Patch<JsonCrdt> {
        val meta = doc[Format.META] as JsonNode.Object
        val value = at?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null
        val leaf = leafFrom(meta.map[Format.META_CLOSED_AT], replica, value)
        return doc.set(Format.META, JsonNode.Object(meta.map.put(replica, Format.META_CLOSED_AT, leaf).delta))
    }

    /**
     * Writes [value] at [pointer] in the data model: an object as a keyed object (each key written
     * the same way, keys it doesn't name left alone), and a scalar or a whole array as one leaf.
     *
     * @throws IllegalArgumentException if a node on the way isn't an object, or the write would
     *   change a path's type (object, append-only array, whole array, scalar)
     */
    fun updateData(doc: JsonCrdt, replica: ReplicaId, pointer: String, value: JsonElement): Patch<JsonCrdt> {
        val segments = Format.segments(pointer)
        require(segments.firstOrNull() != Format.RESERVED_DATA_KEY) { "/${Format.RESERVED_DATA_KEY} is reserved." }
        require(segments.isNotEmpty() || value is JsonObject) { "The data model's root must be an object." }
        if (segments.isEmpty()) {
            require(Format.RESERVED_DATA_KEY !in (value as JsonObject)) { "/${Format.RESERVED_DATA_KEY} is reserved." }
        }
        val delta = writeAt(doc[Format.DATA], segments, 0, replica) { existing, at ->
            writeValue(existing, replica, value, at)
        }
        return doc.set(Format.DATA, delta)
    }

    /**
     * Appends [value] as a new entry of the append-only array at [pointer], creating the array if
     * the path is empty. The entry is one leaf, never edited after.
     *
     * @throws IllegalArgumentException if the path holds anything but an append-only array
     */
    fun appendEntry(doc: JsonCrdt, replica: ReplicaId, pointer: String, value: JsonElement): Patch<JsonCrdt> {
        val segments = Format.segments(pointer)
        require(segments.isNotEmpty()) { "An append-only array can't be the data model's root." }
        require(segments.first() != Format.RESERVED_DATA_KEY) { "/${Format.RESERVED_DATA_KEY} is reserved." }
        val delta = writeAt(doc[Format.DATA], segments, 0, replica) { existing, at ->
            val rga = when (existing) {
                null -> Rga.empty()
                is JsonNode.Array -> existing.rga
                else -> refuse(at, existing, "an append-only array")
            }
            val entry = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, Format.encodeLeaf(value)))
            val (_, op) = rga.insertAt(replica, rga.size, entry)
            JsonNode.Array(Rga.empty<JsonNode>().apply(op))
        }
        return doc.set(Format.DATA, delta)
    }

    fun meta(doc: JsonCrdt): SurfaceMeta {
        val meta = doc[Format.META] as JsonNode.Object
        fun field(key: String): JsonElement = Format.winner(meta.map[key] as JsonNode.Leaf)
        return SurfaceMeta(
            title = field(Format.META_TITLE).jsonPrimitive.content,
            catalogId = field(Format.META_CATALOG_ID).jsonPrimitive.content,
            openedBy = PeerIdentity.parse(field(Format.META_OPENED_BY).jsonPrimitive.content),
            openedAt = Instant.parse(field(Format.META_OPENED_AT).jsonPrimitive.content),
            closedAt = (field(Format.META_CLOSED_AT) as? JsonPrimitive)?.takeIf { it.isString }?.let { Instant.parse(it.content) },
        )
    }

    private fun metaNode(existing: JsonNode?, replica: ReplicaId, meta: SurfaceMeta): JsonNode {
        val fields = listOf(
            Format.META_TITLE to JsonValue.Str(meta.title),
            Format.META_CATALOG_ID to JsonValue.Str(meta.catalogId),
            Format.META_OPENED_BY to JsonValue.Str(meta.openedBy.toString()),
            Format.META_OPENED_AT to JsonValue.Str(meta.openedAt.toString()),
            Format.META_CLOSED_AT to (meta.closedAt?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        )
        val map = objectMap(existing, "/${Format.META}")
        return JsonNode.Object(threadedPuts(map, replica, fields) { _, current, v -> leafFrom(current, replica, v) })
    }

    private fun componentId(component: JsonObject): String {
        val id = component["id"] as? JsonPrimitive
        require(id != null && id.isString) { "A component has no string id: $component" }
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
        val at = Format.pointer(segments.take(depth))
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
                JsonNode.Object(
                    threadedPuts(objectMap(existing, at), replica, entries) { key, current, v ->
                        writeValue(current, replica, v, "$at/${Format.escapeSegment(key)}")
                    },
                )
            }
            else -> {
                val structured = value is JsonArray
                when (existing) {
                    null -> Unit
                    is JsonNode.Leaf -> {
                        val held = existing.register.values.map(Format::isStructured).toSet()
                        if (held.isNotEmpty() && structured !in held) refuse(at, existing, kindOf(value))
                    }
                    else -> refuse(at, existing, kindOf(value))
                }
                leafFrom(existing, replica, Format.encodeLeaf(value))
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
            is JsonNode.Leaf -> if (existing.register.values.any(Format::isStructured)) "a whole array" else "a scalar"
        }
        throw IllegalArgumentException("$at holds $held; writing $wanted there would change its type.")
    }

    /**
     * MIRROR of bee-a2ui's `SurfaceFormat`, which the projection reads with. bee-core can't depend
     * on bee-a2ui yet (tractat-us/bee#8 adds it), so the layout lives in both places until then;
     * fold this into `SurfaceFormat` once it does.
     */
    object Format {
        const val META = "meta"
        const val COMPONENTS = "components"
        const val DATA = "data"
        const val META_TITLE = "title"
        const val META_CATALOG_ID = "catalogId"
        const val META_OPENED_BY = "openedBy"
        const val META_OPENED_AT = "openedAt"
        const val META_CLOSED_AT = "closedAt"
        const val RESERVED_DATA_KEY = "_bee"
        const val STRUCTURED_PREFIX = "\u0000json:"

        val VALUE_ORDER: Comparator<JsonValue> = Comparator { a, b ->
            val byRank = rank(a).compareTo(rank(b))
            if (byRank != 0) {
                byRank
            } else {
                when (a) {
                    JsonValue.Null -> 0
                    is JsonValue.Bool -> a.value.compareTo((b as JsonValue.Bool).value)
                    is JsonValue.Num -> a.value.compareTo((b as JsonValue.Num).value)
                    is JsonValue.Str -> a.value.compareTo((b as JsonValue.Str).value)
                }
            }
        }

        private fun rank(v: JsonValue): Int = when (v) {
            JsonValue.Null -> 0
            is JsonValue.Bool -> 1
            is JsonValue.Num -> 2
            is JsonValue.Str -> 3
        }

        fun encodeLeaf(element: JsonElement): JsonValue = when (element) {
            is JsonObject, is JsonArray -> JsonValue.Str(STRUCTURED_PREFIX + canonical(element))
            JsonNull -> JsonValue.Null
            is JsonPrimitive -> encodeScalar(element)
        }

        private fun encodeScalar(p: JsonPrimitive): JsonValue {
            if (p.isString) {
                require(!p.content.startsWith(STRUCTURED_PREFIX)) { "A data string starts with bee's reserved prefix." }
                return JsonValue.Str(p.content)
            }
            p.booleanOrNull?.let { return JsonValue.Bool(it) }
            return JsonValue.Num(requireNotNull(p.doubleOrNull) { "Not a JSON scalar: $p" })
        }

        fun decodeLeaf(v: JsonValue): JsonElement = when (v) {
            JsonValue.Null -> JsonNull
            is JsonValue.Bool -> JsonPrimitive(v.value)
            is JsonValue.Num -> JsonPrimitive(v.value)
            is JsonValue.Str ->
                if (isStructured(v)) Json.parseToJsonElement(v.value.removePrefix(STRUCTURED_PREFIX)) else JsonPrimitive(v.value)
        }

        fun isStructured(v: JsonValue): Boolean = v is JsonValue.Str && v.value.startsWith(STRUCTURED_PREFIX)

        fun winner(leaf: JsonNode.Leaf): JsonElement =
            leaf.register.values.maxWithOrNull(VALUE_ORDER)?.let(::decodeLeaf) ?: JsonNull

        fun canonical(element: JsonElement): String = sortKeys(element).toString()

        private fun sortKeys(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.keys.sorted().associateWith { sortKeys(element.getValue(it)) })
            is JsonArray -> JsonArray(element.map(::sortKeys))
            is JsonPrimitive -> element
        }

        fun escapeSegment(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

        fun pointer(segments: List<String>): String = segments.joinToString("") { "/" + escapeSegment(it) }

        fun segments(pointer: String): List<String> {
            if (pointer.isEmpty()) return emptyList()
            require(pointer.startsWith("/")) { "A JSON Pointer must start with '/': $pointer" }
            return pointer.substring(1).split("/").map { it.replace("~1", "/").replace("~0", "~") }
        }
    }
}
