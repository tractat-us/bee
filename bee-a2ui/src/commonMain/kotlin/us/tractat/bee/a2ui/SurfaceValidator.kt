package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a surface against the Notions catalog before anyone writes or renders it (A11).
 *
 * The host can't refuse a CRDT write, so writers validate: the bridge before it writes a surface,
 * and the browser driver before it renders one, showing a placeholder that names the errors.
 */
object SurfaceValidator {
    /**
     * The component types the Notions catalog renders today: A2UI v0.9.1's basic catalog plus
     * `Sketch`, `Poll` and `Board` (`bee-notions/src/catalog.js`), so it always matches what the
     * renderer can draw.
     */
    val NOTIONS_COMPONENT_TYPES: Set<String> = setOf(
        "Text", "Image", "Icon", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs",
        "Modal", "Divider", "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput",
        SKETCH, POLL, BOARD,
    )

    /** A question, options and per-peer answers ([NotionActions]): binds `question`, `options` and `answers`. */
    const val POLL = "Poll"

    /** Columns and draggable cards ([NotionActions]): binds `columns` and `cards`. */
    const val BOARD = "Board"

    /** The data bindings each Notions type needs, in the order its errors are named. */
    private val BINDINGS: Map<String, List<String>> = mapOf(
        POLL to listOf("answers", "options", "question"),
        BOARD to listOf("cards", "columns"),
    )

    /** Agent-written HTML in a sandboxed frame (spec §5.6): properties `html` and `title?`. */
    const val SKETCH = "Sketch"

    private const val ROOT_ID = "root"

    /** Every problem with [view], each naming the component, child id or type at fault; empty when valid. */
    fun errors(view: SurfaceView): List<String> = buildList {
        if (view.catalogId != NOTIONS_CATALOG_ID) {
            add("Surface ${view.id} names catalog ${view.catalogId}, not $NOTIONS_CATALOG_ID.")
        }
        if (ROOT_ID !in view.components) add("Surface ${view.id} has no \"$ROOT_ID\" component.")
        view.components.keys.sorted().forEach { id ->
            val component = view.components.getValue(id)
            addAll(typeErrors(id, component))
            references(component)
                .filter { it !in view.components }
                .forEach { add("Component \"$id\" names child \"$it\", which no component has as its id.") }
        }
    }

    private fun typeErrors(id: String, component: JsonObject): List<String> {
        val type = component["component"]?.stringOrNull() ?: return listOf("Component \"$id\" has no type.")
        if (type !in NOTIONS_COMPONENT_TYPES) return listOf("Component \"$id\" has unknown type \"$type\".")
        return when (type) {
            // The frame's whole content: without it the renderer has nothing to sandbox.
            SKETCH -> if (component["html"] == null) listOf("Component \"$id\" is a Sketch with no html.") else emptyList()
            else -> BINDINGS[type].orEmpty()
                .filterNot { isBinding(component[it]) }
                .map { "Component \"$id\" is a $type whose $it isn't a data binding ({\"path\": \"/…\"})." }
        }
    }

    /** A2UI's `DataBinding`: `{"path": "/…"}`, an absolute pointer into the data model. */
    private fun isBinding(property: JsonElement?): Boolean =
        (property as? JsonObject)?.get("path")?.stringOrNull()?.startsWith("/") == true

    /**
     * The component ids [component] names, from every field the basic catalog types as a
     * `ComponentId` or `ChildList`: `child`, `trigger`, `content`, `children` (a list, or a
     * template's `componentId`) and each of `tabs`' `child`.
     */
    private fun references(component: JsonObject): List<String> {
        val single = listOf("child", "trigger", "content").mapNotNull { component[it]?.stringOrNull() }
        val children = when (val c = component["children"]) {
            is JsonArray -> c.mapNotNull { it.stringOrNull() }
            is JsonObject -> listOfNotNull(c["componentId"]?.stringOrNull())
            else -> emptyList()
        }
        val tabs = (component["tabs"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("child")?.stringOrNull() }
        return single + children + tabs
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
