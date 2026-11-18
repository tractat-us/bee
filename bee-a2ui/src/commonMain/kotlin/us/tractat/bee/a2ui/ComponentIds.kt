package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a component id crosses the renderer (bee#230).
 *
 * A2UI v1.0 refuses a component id that isn't a UAX #31 identifier, and bee's boards hold ids
 * such as `admit-button`, written before that rule. A board keeps its ids as written, and agents
 * only ever see their own: the page shows the renderer each id as [rendered] gives it, and maps
 * every id the renderer reports back through [stored].
 *
 * An id that is already plain, ASCII letters, digits and `_` not starting with a digit, crosses
 * as itself, so `admit_button` and `root` are unchanged. Any other id is escaped: [PREFIX], then
 * each ASCII letter or digit as itself and every other UTF-16 unit as `_<hex>_`, so `admit-button`
 * crosses as `_bee_admit_2d_button`. A plain id that starts with [PREFIX] is escaped too, so no
 * escaped id is ever an id someone wrote: the mapping is injective, and [stored] inverts it.
 */
object ComponentIds {
    /** What every escaped id starts with, and so what no plain id may. */
    const val PREFIX = "_bee_"

    private const val ESCAPE = '_'
    private const val HEX = 16

    /** [id] as the renderer is shown it: a v1.0 identifier, and [id] itself when [id] is plain. */
    fun rendered(id: String): String = if (isPlain(id)) id else escape(id)

    /**
     * The id [rendered] took to [renderedId]: what the board stores and agents see.
     *
     * @throws IllegalArgumentException if [renderedId] starts with [PREFIX] but [rendered] never
     *   gives it, so it can't have come from a component bee rendered
     */
    fun stored(renderedId: String): String {
        if (!renderedId.startsWith(PREFIX)) return renderedId
        val id = unescape(renderedId)
        require(rendered(id) == renderedId) { "Component id $renderedId isn't one bee renders." }
        return id
    }

    /**
     * [component] as the renderer is shown it: its `id`, and every id it names ([references]),
     * through [rendered]. Nothing else changes.
     */
    fun rendered(component: JsonObject): JsonObject = JsonObject(
        component.mapValues { (key, value) ->
            when (key) {
                ID, CHILD, TRIGGER, CONTENT -> renderedIdOrAsIs(value)
                CHILDREN -> renderedChildren(value)
                TABS -> renderedTabs(value)
                else -> value
            }
        },
    )

    /**
     * The component ids [component] names, from every property the basic catalog types as a
     * `ComponentId` or `ChildList`: `child`, `trigger`, `content`, `children` (a list, or a
     * template's `componentId`) and each of `tabs`' `child`. The Notions components name none.
     */
    fun references(component: JsonObject): List<String> {
        val single = listOf(CHILD, TRIGGER, CONTENT).mapNotNull { component[it]?.stringOrNull() }
        val children = when (val c = component[CHILDREN]) {
            is JsonArray -> c.mapNotNull { it.stringOrNull() }
            is JsonObject -> listOfNotNull(c[TEMPLATE_ID]?.stringOrNull())
            else -> emptyList()
        }
        val tabs = (component[TABS] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get(CHILD)?.stringOrNull() }
        return single + children + tabs
    }

    private const val ID = "id"
    private const val CHILD = "child"
    private const val TRIGGER = "trigger"
    private const val CONTENT = "content"
    private const val CHILDREN = "children"
    private const val TEMPLATE_ID = "componentId"
    private const val TABS = "tabs"

    // A value that isn't a string is left for SurfaceValidator to name; it's never an id.
    private fun renderedIdOrAsIs(value: JsonElement): JsonElement =
        value.stringOrNull()?.let { JsonPrimitive(rendered(it)) } ?: value

    private fun renderedChildren(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::renderedIdOrAsIs))
        is JsonObject -> JsonObject(value.mapValues { (key, v) -> if (key == TEMPLATE_ID) renderedIdOrAsIs(v) else v })
        else -> value
    }

    private fun renderedTabs(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(
            value.map { tab ->
                (tab as? JsonObject)?.let { JsonObject(it.mapValues { (key, v) -> if (key == CHILD) renderedIdOrAsIs(v) else v }) }
                    ?: tab
            },
        )
        else -> value
    }

    private fun isPlain(id: String): Boolean =
        id.isNotEmpty() && !id.first().isAsciiDigit() && id.all { it.isAsciiAlphanumeric() || it == ESCAPE } &&
            !id.startsWith(PREFIX)

    private fun escape(id: String): String = buildString {
        append(PREFIX)
        id.forEach { unit ->
            if (unit.isAsciiAlphanumeric()) append(unit) else append(ESCAPE).append(unit.code.toString(HEX)).append(ESCAPE)
        }
    }

    private fun unescape(renderedId: String): String = buildString {
        var at = PREFIX.length
        while (at < renderedId.length) {
            val unit = renderedId[at]
            if (unit != ESCAPE) {
                append(unit)
                at++
            } else {
                val end = renderedId.indexOf(ESCAPE, at + 1)
                require(end > at + 1) { "Component id $renderedId isn't one bee renders." }
                val code = requireNotNull(renderedId.substring(at + 1, end).toIntOrNull(HEX)?.takeIf { it in 0..Char.MAX_VALUE.code }) {
                    "Component id $renderedId isn't one bee renders."
                }
                append(code.toChar())
                at = end + 1
            }
        }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isAsciiAlphanumeric(): Boolean = isAsciiDigit() || this in 'a'..'z' || this in 'A'..'Z'

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
