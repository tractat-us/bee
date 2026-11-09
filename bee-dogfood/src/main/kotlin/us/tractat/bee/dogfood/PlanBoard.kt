package us.tractat.bee.dogfood

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.security.MessageDigest

/** `docs/project/plan.yaml`, read as the plain JSON its header promises: no anchors, no tags. */
object PlanYaml {
    fun parse(text: String): JsonObject {
        val loaded = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
        return requireNotNull(toJson(loaded) as? JsonObject) { "plan.yaml must hold an object at its top." }
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("plan.yaml holds a ${value::class.simpleName}, which isn't plain JSON: $value")
    }
}

/**
 * The plan as a `Board` notion (bee#21): one column per status in `column_order`, one card per item
 * in `order`. A card's label is all a `Board` card shows, so it carries the item's GitHub reference,
 * its title and, unless it's done, the first sentence of its summary.
 *
 * Invariant 1 (a physical whiteboard, no clutter): the done column shows only its [DONE_SHOWN] most
 * recently landed cards, and one card counting the rest.
 *
 * A data write on the board merges objects and never removes a key (bee#19), so a card dropped
 * from the plan would linger. Each seed therefore writes the board under a key named for the hash
 * of its column and card ids, and points the `Board`'s bindings there. A card added or removed
 * (the done cap moving included) gets a fresh key, so the board shows exactly this plan; a card
 * that only changes its label or column overwrites in place under the same key. Each set of ids
 * leaves its old key behind until bee#19 gives a write that replaces.
 */
object PlanBoard {
    const val SURFACE_ID = "dogfood-plan"
    const val TITLE = "Plan"
    const val DONE_SHOWN = 5
    const val SUMMARY_CHARS = 60
    const val EARLIER_DONE = "done-earlier"
    private const val DONE = "done"

    fun surface(plan: JsonObject): SeedSurface {
        val board = buildJsonObject {
            put("columns", columns(plan))
            put("cards", cards(plan))
        }
        val ids = listOf("columns", "cards").map { board.obj(it).obj("items").keys.sorted() }
        val key = "plan-${hash(JsonArray(ids.map { JsonArray(it.map(::JsonPrimitive)) }))}"
        val root = buildJsonObject {
            put("id", SeedSurface.ROOT)
            put("component", "Board")
            put("columns", Components.binding("/$key/columns"))
            put("cards", Components.binding("/$key/cards"))
        }
        return SeedSurface(SURFACE_ID, TITLE, listOf(root), buildJsonObject { put(key, board) })
    }

    /** A card's label: `bee#21 · Title — First sentence of the summary.` */
    fun label(item: JsonObject): String {
        val title = item.string("title")
        val head = listOfNotNull(ref(item), title).joinToString(" · ")
        val summary = item.stringOrNull("summary")?.takeUnless { item.string("status") == DONE }
        return if (summary == null) head else "$head — ${short(summary)}"
    }

    /** The item's first GitHub issue or PR, as `<repo>#<n>`, or null if it links none. */
    fun ref(item: JsonObject): String? = item["links"]?.jsonArray.orEmpty()
        .firstNotNullOfOrNull { link -> GITHUB.find(link.jsonObject.string("url")) }
        ?.let { "${it.groupValues[1]}#${it.groupValues[2]}" }

    /** [text]'s first sentence, cut to [SUMMARY_CHARS] at a word with an ellipsis. */
    fun short(text: String): String {
        val sentence = SENTENCE_END.find(text)?.let { text.substring(0, it.range.first + 1) } ?: text
        if (sentence.length <= SUMMARY_CHARS) return sentence
        val cut = sentence.substring(0, SUMMARY_CHARS).substringBeforeLast(' ')
        return "${cut.trimEnd(',', ';', ':', ' ')}…"
    }

    private fun columns(plan: JsonObject): JsonObject {
        val defs = plan.obj("columns")
        val counts = items(plan).values.groupingBy { it.string("status") }.eachCount()
        val order = plan.getValue("column_order").jsonArray.map { it.jsonPrimitive.content }
        return collection(
            order.associateWith { status ->
                val def = defs.obj(status)
                buildJsonObject {
                    put("title", "${def.string("title")} (${counts[status] ?: 0})")
                    def.stringOrNull("owner")?.let { put("owner", it) }
                }
            },
        )
    }

    private fun cards(plan: JsonObject): JsonObject {
        val items = items(plan)
        val order = plan.getValue("order").jsonArray.map { it.jsonPrimitive.content }.filter { it in items }
        val done = order.filter { items.getValue(it).string("status") == DONE }
        val shownDone = done.sortedByDescending { items.getValue(it).stringOrNull("landed").orEmpty() }.take(DONE_SHOWN).toSet()
        val shown = order.filter { items.getValue(it).string("status") != DONE || it in shownDone }
        val cards = shown.associateWith { id -> card(label(items.getValue(id)), items.getValue(id).string("status")) }
        val earlier = done.size - shownDone.size
        val all = if (earlier > 0) cards + (EARLIER_DONE to card("+ $earlier earlier, in docs/project/plan.yaml", DONE)) else cards
        return collection(all)
    }

    private fun card(label: String, column: String) = buildJsonObject {
        put("label", label)
        put("column", column)
    }

    /** An ordered collection `{items, order}`, in [items]' iteration order. */
    private fun collection(items: Map<String, JsonObject>) = buildJsonObject {
        put("items", JsonObject(items))
        put("order", JsonArray(items.keys.map(::JsonPrimitive)))
    }

    private fun items(plan: JsonObject): Map<String, JsonObject> = plan.obj("items").mapValues { it.value.jsonObject }

    private fun hash(json: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    private val GITHUB = Regex("""^https://github\.com/tractat-us/([A-Za-z0-9_.-]+)/(?:issues|pull)/(\d+)""")
    private val SENTENCE_END = Regex("""[.!?](?=\s|$)""")
}

internal fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject) { "\"$key\" must be an object." }

internal fun JsonObject.string(key: String): String = requireNotNull(stringOrNull(key)) { "\"$key\" must be a string." }

internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
