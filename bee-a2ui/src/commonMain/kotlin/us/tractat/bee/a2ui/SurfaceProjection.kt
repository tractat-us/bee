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
 * A leaf holds a `JsonValue`, which has no array or object. A leaf that holds a whole array (or an
 * object, as an array entry) holds a [JsonValue.Str] of its canonical JSON behind
 * [STRUCTURED_PREFIX]. A scalar data string never starts with that prefix, because writers refuse
 * one. This encoding is the current behaviour, and whether to keep it is a decision pending with
 * Iain (tractat-us/bee PR 9). Meta strings aren't data: they're stored and read raw, so any title
 * is safe.
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

    /** Marks a leaf string as canonical JSON for an array or object, never a scalar string. */
    const val STRUCTURED_PREFIX = "\u0000json:"

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
            require(!p.content.startsWith(STRUCTURED_PREFIX)) { "A data string starts with bee's reserved prefix." }
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
 * its pointer. An append-only array shows its entries in sequence order, and its pointer is in
 * [SurfaceView.appended]. A whole array shows as
 * its writer wrote it: it's one leaf, so a merge can't put an id in it twice, and nothing is
 * deduped. Each component shows in A2UI v1.0's spelling, even one saved under v0.9.1
 * ([A2uiBindings.upgrade]).
 */
fun JsonCrdt.toSurfaceView(id: String): SurfaceView {
    val meta = requireNotNull(this[SurfaceFormat.META] as? JsonNode.Object) { "Surface $id has no meta." }
    val catalogId = requireNotNull(
        (meta.map[SurfaceFormat.META_CATALOG_ID] as? JsonNode.Leaf)?.let(SurfaceFormat::metaString),
    ) { "Surface $id has no catalogId." }
    val componentMap =
        requireNotNull(this[SurfaceFormat.COMPONENTS] as? JsonNode.Object) { "Surface $id has no components." }.map
    val components = componentMap.keys.sorted().associateWith { key ->
        A2uiBindings.upgrade(SurfaceFormat.winner(componentMap[key] as JsonNode.Leaf).jsonObject)
    }
    val conflicts = mutableMapOf<String, List<JsonElement>>()
    val appended = mutableSetOf<String>()
    val dataNode = requireNotNull(this[SurfaceFormat.DATA] as? JsonNode.Object) { "Surface $id has no data object." }
    val data = project(dataNode, "", conflicts, appended) as JsonObject
    return SurfaceView(
        id = id,
        catalogId = catalogId,
        components = components,
        data = data,
        conflicts = conflicts.toMap(),
        appended = appended.toSet(),
    )
}

private fun project(
    node: JsonNode,
    pointer: String,
    conflicts: MutableMap<String, List<JsonElement>>,
    appended: MutableSet<String>,
): JsonElement =
    when (node) {
        is JsonNode.Object -> JsonObject(
            node.map.keys.sorted().associateWith { key ->
                project(node.map[key]!!, "$pointer/${SurfaceFormat.escapeSegment(key)}", conflicts, appended)
            },
        )
        is JsonNode.Array -> {
            appended += pointer
            JsonArray(node.rga.toList().mapIndexed { index, entry -> project(entry, "$pointer/$index", conflicts, appended) })
        }
        is JsonNode.Leaf -> {
            val values = node.register.values.sortedWith(SurfaceFormat.VALUE_ORDER)
            if (values.size > 1) conflicts[pointer] = values.map(SurfaceFormat::decodeLeaf)
            values.lastOrNull()?.let(SurfaceFormat::decodeLeaf) ?: JsonNull
        }
    }
