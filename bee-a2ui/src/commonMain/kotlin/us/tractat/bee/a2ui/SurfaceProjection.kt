package us.tractat.bee.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import kotlin.math.abs

/**
 * How a surface is laid out in its `JsonCrdt` document, and how a leaf holds JSON (spec §5.3, A12).
 *
 * A surface document has three keys. `meta` is an object of scalar leaves. `components` is an
 * object keyed by component id, each value one leaf holding the component's canonical JSON. `data`
 * is the A2UI data model: objects are keyed objects, scalars are leaves, a JSON array written as
 * data is one leaf holding the whole array, and an append-only array is a `JsonNode.Array` whose
 * entries are leaves.
 *
 * An **ordered collection** is a data object with exactly two members: `items`, a keyed object of
 * items by id, and `order`, an append-only array of their ids (A12, tractat-us/bee#14). An id is appended
 * once and never edited, so two peers adding items at once both land. `order` may be absent, as it
 * is before the first append. The projection shows the order as a permutation of the item ids;
 * see [toSurfaceView].
 *
 * A leaf holds a `JsonValue`, which has no array or object. A leaf that holds a whole array (or an
 * object, as an array entry) holds a [JsonValue.Str] of its canonical JSON behind
 * [STRUCTURED_PREFIX]. A leaf a replace cleared holds [CLEARED]. Both live in a namespace of
 * strings starting with [RESERVED_PREFIX], and writers refuse a data string that starts with it, so
 * no scalar is mistaken for either. **This is a persisted format**, confirmed for v1
 * (tractat-us/bee#14): boards on disk and on the wire hold it, so changing it needs a migration. A
 * structured `JsonValue` case, which would retire the JSON prefix, is asked for in
 * tractat-us/kuilt#3119. Meta strings aren't data: they're stored and read raw, so any title is
 * safe.
 *
 * bee-core's `SurfaceDoc` writes this layout through these functions, and the projection reads it
 * with them, so the format lives once.
 */
object SurfaceFormat {
    const val META = "meta"
    const val COMPONENTS = "components"
    const val DATA = "data"

    const val META_TITLE = "title"
    const val META_CATALOG_ID = "catalogId"
    const val META_OPENED_BY = "openedBy"
    const val META_OPENED_AT = "openedAt"
    const val META_CLOSED_AT = "closedAt"

    /** The data key the driver publishes conflicts under, at `/_bee/conflicts/<escaped pointer>`. */
    const val RESERVED_DATA_KEY = "_bee"

    /** An ordered collection's keyed object of items. */
    const val ITEMS = "items"

    /** An ordered collection's append-only array of item ids. */
    const val ORDER = "order"

    /**
     * Marks a leaf string as canonical JSON for an array or object, never a scalar string. Part of
     * v1's persisted format (tractat-us/bee#14); tractat-us/kuilt#3119 asks for its replacement.
     */
    const val STRUCTURED_PREFIX = "\u0000json:"

    /** Starts every string bee reserves in a data leaf. Writers refuse a data string with it. */
    const val RESERVED_PREFIX = "\u0000"

    /**
     * What a replace writes into a leaf it clears (tractat-us/bee#14): the projection hides it,
     * and the leaf keeps its register, so its dots never restart. Part of v1's persisted format.
     */
    val CLEARED: JsonValue = JsonValue.Str("\u0000cleared")

    /** Whether a leaf value is [CLEARED]. */
    fun isCleared(v: JsonValue): Boolean = v == CLEARED

    /**
     * The projection's total order on leaf values (design call, spec §5.3): `Null` < `Bool` <
     * `Num` < `Str`, then by value, with strings compared by UTF-16 code unit. The winner is the
     * greatest value, so every peer shows the same one, whatever order merges arrived in.
     */
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

    /** The leaf value for [element]: a scalar as itself, an array or object as prefixed canonical JSON. */
    fun encodeLeaf(element: JsonElement): JsonValue = when (element) {
        is JsonObject, is JsonArray -> JsonValue.Str(STRUCTURED_PREFIX + canonical(element))
        JsonNull -> JsonValue.Null
        is JsonPrimitive -> encodeScalar(element)
    }

    private fun encodeScalar(p: JsonPrimitive): JsonValue {
        if (p.isString) {
            require(!p.content.startsWith(RESERVED_PREFIX)) { "A data string starts with bee's reserved prefix, U+0000." }
            return JsonValue.Str(p.content)
        }
        p.booleanOrNull?.let { return JsonValue.Bool(it) }
        val number = requireNotNull(p.doubleOrNull) { "Not a JSON scalar: $p" }
        return JsonValue.Num(number)
    }

    /** The JSON a leaf value [v] stands for: the inverse of [encodeLeaf]. */
    fun decodeLeaf(v: JsonValue): JsonElement = when (v) {
        JsonValue.Null -> JsonNull
        is JsonValue.Bool -> JsonPrimitive(v.value)
        is JsonValue.Num -> decodeNumber(v.value)
        is JsonValue.Str ->
            if (v.value.startsWith(STRUCTURED_PREFIX)) {
                Json.parseToJsonElement(v.value.removePrefix(STRUCTURED_PREFIX))
            } else {
                JsonPrimitive(v.value)
            }
    }

    /** Whether a leaf value holds a whole array or object rather than a scalar. */
    fun isStructured(v: JsonValue): Boolean = v is JsonValue.Str && v.value.startsWith(STRUCTURED_PREFIX)

    // A JSON integer comes back as one, so `3` doesn't render as `3.0`.
    private fun decodeNumber(d: Double): JsonPrimitive =
        if (d % 1.0 == 0.0 && abs(d) <= MAX_SAFE_INTEGER) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

    /** [element] with every object's keys sorted, compact: the one stored form of a JSON value. */
    fun canonical(element: JsonElement): String = sortKeys(element).toString()

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.keys.sorted().associateWith { sortKeys(element.getValue(it)) })
        is JsonArray -> JsonArray(element.map(::sortKeys))
        is JsonPrimitive -> element
    }

    /** One JSON Pointer reference token, escaped (RFC 6901). */
    fun escapeSegment(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

    /** The JSON Pointer naming [segments], each escaped. */
    fun pointer(segments: List<String>): String = segments.joinToString("") { "/" + escapeSegment(it) }

    /**
     * The string a meta leaf shows, read raw (meta never goes through [encodeLeaf]): its greatest
     * value under [VALUE_ORDER], or `null` when that value is JSON null.
     *
     * @throws IllegalArgumentException if the leaf is empty or holds something but a string or null
     */
    fun metaString(leaf: JsonNode.Leaf): String? =
        when (val v = requireNotNull(leaf.register.values.maxWithOrNull(VALUE_ORDER)) { "An empty meta leaf." }) {
            JsonValue.Null -> null
            is JsonValue.Str -> v.value
            else -> throw IllegalArgumentException("A meta leaf holds $v, not a string.")
        }

    /** The value [leaf] shows: its greatest under [VALUE_ORDER], or `null` JSON if it holds none. */
    fun winner(leaf: JsonNode.Leaf): JsonElement =
        leaf.register.values.maxWithOrNull(VALUE_ORDER)?.let(::decodeLeaf) ?: JsonNull

    /**
     * The reference tokens of [pointer], unescaped. `""` and `"/"` are both the root and have
     * none: A2UI reads `"/"` as the root, so bee never uses RFC 6901's empty key.
     *
     * @throws IllegalArgumentException on a pointer without a leading `/`, or with an empty token
     */
    fun segments(pointer: String): List<String> {
        if (pointer.isEmpty() || pointer == "/") return emptyList()
        require(pointer.startsWith("/")) { "A JSON Pointer must start with '/': $pointer" }
        val segments = pointer.substring(1).split("/").map { it.replace("~1", "/").replace("~0", "~") }
        require(segments.none { it.isEmpty() }) { "A JSON Pointer has an empty token: $pointer" }
        return segments
    }
}

/**
 * The surface stored in this document under id [id], projected for display.
 *
 * Every leaf shows one value, the greatest under [SurfaceFormat.VALUE_ORDER]. A data leaf holding
 * several concurrent values also lists them all, in that order, in [SurfaceView.conflicts] under
 * its pointer. An append-only array shows its entries in sequence order. A whole array shows as
 * its writer wrote it: it's one leaf, so a merge can't put an id in it twice, and nothing is
 * deduped. A leaf a replace cleared is hidden; see [SurfaceFormat.CLEARED].
 *
 * An ordered collection's `order` shows each of its item ids exactly once: the order's ids in
 * sequence, each at its first occurrence, without ids that name no item, then any item the order
 * lacks, sorted by id. An item can lack its id when a peer's edit lands on an item another peer
 * cleared, or when its write arrived before its append. Every replica holding the same state shows
 * the same permutation.
 */
fun JsonCrdt.toSurfaceView(id: String): SurfaceView {
    val meta = requireNotNull(this[SurfaceFormat.META] as? JsonNode.Object) { "Surface $id has no meta." }
    val catalogId = requireNotNull(
        (meta.map[SurfaceFormat.META_CATALOG_ID] as? JsonNode.Leaf)?.let(SurfaceFormat::metaString),
    ) { "Surface $id has no catalogId." }
    val componentMap =
        requireNotNull(this[SurfaceFormat.COMPONENTS] as? JsonNode.Object) { "Surface $id has no components." }.map
    val components = componentMap.keys.sorted().associateWith { key ->
        SurfaceFormat.winner(componentMap[key] as JsonNode.Leaf).jsonObject
    }
    val conflicts = mutableMapOf<String, List<JsonElement>>()
    val dataNode = requireNotNull(this[SurfaceFormat.DATA] as? JsonNode.Object) { "Surface $id has no data object." }
    val data = checkNotNull(project(dataNode, "", conflicts, root = true)) as JsonObject
    return SurfaceView(
        id = id,
        catalogId = catalogId,
        components = components,
        data = data,
        conflicts = conflicts.toMap(),
    )
}

/**
 * [node] as shown, or `null` for a **husk**, which its parent object then omits. A replace never
 * removes a node (tractat-us/bee#14), so a husk is what it leaves behind:
 * - a leaf holding only [SurfaceFormat.CLEARED];
 * - an append-only array with no entry left. bee creates one only by appending, so an empty one
 *   was cleared. An empty array written as data is a leaf, not a husk, and shows as `[]`;
 * - an object that holds keys but shows none of them, unless it's the data model's root or an
 *   ordered collection.
 *
 * A cleared value beside a concurrent one is dropped, so the concurrent value shows, and isn't
 * reported as a conflict with the clear.
 */
private fun project(
    node: JsonNode,
    pointer: String,
    conflicts: MutableMap<String, List<JsonElement>>,
    root: Boolean = false,
): JsonElement? =
    when (node) {
        is JsonNode.Object -> projectObject(node, pointer, conflicts, root)
        is JsonNode.Array -> node.rga.toList().takeIf { it.isNotEmpty() }?.let { entries ->
            JsonArray(
                entries.mapIndexed { index, entry ->
                    project(entry, "$pointer/$index", conflicts) ?: error("$pointer/$index: an appended entry is never cleared.")
                },
            )
        }
        is JsonNode.Leaf -> {
            val held = node.register.values
            val values = held.filterNot(SurfaceFormat::isCleared).sortedWith(SurfaceFormat.VALUE_ORDER)
            if (values.size > 1) conflicts[pointer] = values.map(SurfaceFormat::decodeLeaf)
            when {
                values.isNotEmpty() -> SurfaceFormat.decodeLeaf(values.last())
                held.isEmpty() -> JsonNull
                else -> null
            }
        }
    }

/**
 * [node] with its husks omitted, or `null` when it's a husk itself; see [project]. An ordered
 * collection always shows, its `items` as `{}` when every item was cleared, so emptying a board
 * leaves an empty board rather than none.
 */
private fun projectObject(
    node: JsonNode.Object,
    pointer: String,
    conflicts: MutableMap<String, List<JsonElement>>,
    root: Boolean,
): JsonObject? {
    val members = node.map.keys.sorted().mapNotNull { key ->
        project(node.map[key]!!, "$pointer/${SurfaceFormat.escapeSegment(key)}", conflicts)?.let { key to it }
    }.toMap()
    if (isOrderedCollection(node, members)) {
        val shown = members + (SurfaceFormat.ITEMS to (members[SurfaceFormat.ITEMS] ?: JsonObject(emptyMap())))
        return JsonObject(shown + (SurfaceFormat.ORDER to orderOf(shown)))
    }
    return JsonObject(members).takeUnless { members.isEmpty() && node.map.keys.isNotEmpty() && !root }
}

/**
 * Whether [node], projected as [members], is an ordered collection: it stores `items` as an
 * object and `order`, if present, as an append-only array, and it shows no other member. A
 * husk, such as an extra object or array a replace cleared, isn't shown, so it doesn't count.
 */
private fun isOrderedCollection(node: JsonNode.Object, members: Map<String, JsonElement>): Boolean =
    node.map[SurfaceFormat.ITEMS] is JsonNode.Object &&
        node.map[SurfaceFormat.ORDER].let { it == null || it is JsonNode.Array } &&
        (members.keys - setOf(SurfaceFormat.ITEMS, SurfaceFormat.ORDER)).isEmpty()

/**
 * An ordered collection's projected [members], its order made a permutation of its item ids. An
 * absent order lists no ids, so the items show sorted by id. A hidden item (a husk, so a deleted
 * card) isn't among the members, so its id is dropped. Items aren't otherwise read, so a fragment
 * item (a card with no `label`) projects as whatever it holds.
 */
private fun orderOf(members: Map<String, JsonElement>): JsonArray {
    val items = (members.getValue(SurfaceFormat.ITEMS) as JsonObject).keys
    val listed = (members[SurfaceFormat.ORDER] as JsonArray? ?: JsonArray(emptyList()))
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .filter { it in items }
        .distinct()
    return JsonArray((listed + (items - listed.toSet()).sorted()).map(::JsonPrimitive))
}
