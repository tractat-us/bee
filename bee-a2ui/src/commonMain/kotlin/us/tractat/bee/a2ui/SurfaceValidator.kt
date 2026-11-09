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

    /**
     * Columns and draggable cards ([NotionActions]): binds `columns` and `cards`. A card is
     * `{label, column, href?, detail?}`: its label links to `href`, an `http:` or `https:` URL,
     * and `detail` is one muted line under it ([cardErrors]).
     */
    const val BOARD = "Board"

    /** The URL schemes a Board card's `href` may use. The page opens it, so never `javascript:`. */
    private val CARD_HREF_SCHEMES = listOf("http://", "https://")

    /** The data bindings each Notions type needs, in the order its errors are named. */
    private val BINDINGS: Map<String, List<String>> = mapOf(
        POLL to listOf("answers", "options", "question"),
        BOARD to listOf("cards", "columns"),
    )

    /**
     * The bindings of each Notions type whose data a basis must name, so each holds appended
     * entries, never data (spec §5.6, §6.1): a Poll's options and question revisions.
     */
    private val APPENDED_BINDINGS: Map<String, List<String>> = mapOf(
        POLL to listOf("options", "question"),
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
            addAll(cardErrors(id, component, view.data))
            references(component)
                .filter { it !in view.components }
                .forEach { add("Component \"$id\" names child \"$it\", which no component has as its id.") }
        }
    }

    /**
     * Every binding in [view] that a basis must name but whose data isn't appended: a Poll's
     * question or options written as data, as a whole array or a scalar (bee#81). A bound path
     * that holds nothing yet is fine, since entries can be appended later.
     *
     * Writers check this; [errors] doesn't, so a Poll written before the bridge could append still
     * renders, without its options basis.
     */
    fun appendErrors(view: SurfaceView): List<String> = view.components.keys.sorted().flatMap { id ->
        val component = view.components.getValue(id)
        val type = component["component"]?.stringOrNull()
        APPENDED_BINDINGS[type].orEmpty().mapNotNull { binding ->
            val path = (component[binding] as? JsonObject)?.get("path")?.stringOrNull() ?: return@mapNotNull null
            val pointer = heldAt(view.data, path) ?: return@mapNotNull null
            if (pointer in view.appended) {
                null
            } else {
                "Component \"$id\" is a $type whose $binding at $path isn't appended: put it in append, not data, " +
                    "so a vote's basis can name each entry."
            }
        }
    }

    /**
     * The problems with the cards a Board [component] binds in [data]: an `href` that isn't an
     * `http:` or `https:` URL string (empty is fine: it clears the link), or a `detail` that isn't a string. Cards not written yet are fine.
     */
    private fun cardErrors(id: String, component: JsonObject, data: JsonObject): List<String> {
        if (component["component"]?.stringOrNull() != BOARD) return emptyList()
        val path = (component["cards"] as? JsonObject)?.get("path")?.stringOrNull() ?: return emptyList()
        val items = (valueAt(data, path) as? JsonObject)?.get("items") as? JsonObject ?: return emptyList()
        return items.keys.sorted().flatMap { cardId ->
            val card = items.getValue(cardId) as? JsonObject ?: return@flatMap emptyList()
            listOfNotNull(
                card["href"]?.takeUnless { it.stringOrNull() == "" || isCardHref(it) }?.let {
                    "Component \"$id\" is a Board whose card \"$cardId\" has href $it, which isn't an http: or https: URL."
                },
                card["detail"]?.takeUnless { it.stringOrNull() != null }?.let {
                    "Component \"$id\" is a Board whose card \"$cardId\" has detail $it, which isn't a string."
                },
            )
        }
    }

    /** Whether [href] is a string naming an `http:` or `https:` URL, case aside. */
    private fun isCardHref(href: JsonElement): Boolean {
        val url = href.stringOrNull()?.lowercase() ?: return false
        return CARD_HREF_SCHEMES.any { url.startsWith(it) && url.length > it.length }
    }

    /** The value [data] holds at [path], or null if it holds none or [path] isn't a pointer. */
    private fun valueAt(data: JsonObject, path: String): JsonElement? {
        val segments = try {
            SurfaceFormat.segments(path)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return segments.fold<String, JsonElement?>(data) { at, key ->
            when (at) {
                is JsonObject -> at[key]
                is JsonArray -> key.toIntOrNull()?.let(at::getOrNull)
                else -> null
            }
        }
    }

    /** The normalized pointer of [path] if [data] holds a value there, else null. */
    private fun heldAt(data: JsonObject, path: String): String? =
        valueAt(data, path)?.let { SurfaceFormat.pointer(SurfaceFormat.segments(path)) }

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
