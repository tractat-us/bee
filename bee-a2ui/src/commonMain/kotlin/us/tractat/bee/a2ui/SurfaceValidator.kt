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
     * The component types the Notions catalog renders today: A2UI v1.0's basic catalog plus
     * `Sketch`, `Poll`, `Board` and `Chat` (`bee-notions/src/catalog.js`), so it always matches what the
     * renderer can draw.
     */
    val NOTIONS_COMPONENT_TYPES: Set<String> = setOf(
        "Text", "Image", "Icon", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs",
        "Modal", "Divider", "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput",
        SKETCH, POLL, BOARD, CHAT,
    )

    /** A question, options and per-peer answers ([NotionActions]): binds `question`, `options` and `answers`. */
    const val POLL = "Poll"

    /**
     * Columns and draggable cards ([NotionActions]): binds `columns` and `cards`. A card is
     * `{label, column, href?, detail?}`: its label links to `href`, an `http:` or `https:` URL,
     * and `detail` is one muted line under it ([cardErrors]).
     */
    const val BOARD = "Board"

    /** People and agents talking: binds `messages`, appended `{id, author, text, at}` ([NotionActions]). */
    const val CHAT = "Chat"

    /** The URL schemes a Board card's `href` may use. The page opens it, so never `javascript:`. */
    private val CARD_HREF_SCHEMES = listOf("http://", "https://")

    /** The data bindings each Notions type needs, in the order its errors are named. */
    private val BINDINGS: Map<String, List<String>> = mapOf(
        POLL to listOf("answers", "options", "question"),
        BOARD to listOf("cards", "columns"),
        CHAT to listOf("messages"),
    )

    /**
     * The bindings of each Notions type whose data a basis must name, so each holds appended
     * entries, never data (spec §5.6, §6.1): a Poll's options and question revisions, and a Chat's
     * messages.
     */
    private val APPENDED_BINDINGS: Map<String, List<String>> = mapOf(
        POLL to listOf("options", "question"),
        CHAT to listOf("messages"),
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
     * It names a malformed Chat message too ([messageErrors]).
     *
     * Writers check this; [errors] doesn't, so a Poll written before the bridge could append still
     * renders, without its options basis, and one bad message, which nobody can edit, never hides
     * a whole Chat.
     */
    fun appendErrors(view: SurfaceView): List<String> = view.components.keys.sorted().flatMap { id ->
        val component = view.components.getValue(id)
        val type = component["component"]?.stringOrNull()
        unappendedErrors(id, type, component, view) + messageErrors(id, component, view.data)
    }

    private fun unappendedErrors(id: String, type: String?, component: JsonObject, view: SurfaceView): List<String> =
        APPENDED_BINDINGS[type].orEmpty().mapNotNull { binding ->
            val path = A2uiBindings.pathOf(component[binding]) ?: return@mapNotNull null
            val pointer = heldAt(view.data, path) ?: return@mapNotNull null
            if (pointer in view.appended) {
                null
            } else {
                "Component \"$id\" is a $type whose $binding at $path isn't appended: put it in append, not data, " +
                    "so a vote's basis can name each entry."
            }
        }

    /**
     * The problems with the messages a Chat [component] binds in [data]: each must be
     * `{id, author, text, at}`, every one a string, its text pass [NotionActions.textProblem], and
     * its id be held by no earlier message, so a reused id fails loudly rather than hiding a
     * message. Messages not written yet are fine.
     */
    private fun messageErrors(id: String, component: JsonObject, data: JsonObject): List<String> {
        if (component["component"]?.stringOrNull() != CHAT) return emptyList()
        val path = A2uiBindings.pathOf(component["messages"]) ?: return emptyList()
        val messages = valueAt(data, path) as? JsonArray ?: return emptyList()
        val at = SurfaceFormat.pointer(SurfaceFormat.segments(path))
        val firstHeld = mutableMapOf<String, Int>()
        return messages.mapIndexedNotNull { index, entry ->
            val fields = (entry as? JsonObject)?.let { message -> MESSAGE_FIELDS.associateWith { message[it]?.stringOrNull() } }
            val text = fields?.get("text")
            val messageId = fields?.get("id")
            val problem = if (fields == null || null in fields.values || text == null || messageId == null) {
                "isn't {${MESSAGE_FIELDS.joinToString()}}, each a string"
            } else {
                val first = firstHeld.getOrPut(messageId) { index }
                NotionActions.textProblem(text) ?: "has id \"$messageId\", which $at/$first holds".takeIf { first != index }
            }
            problem?.let { "Component \"$id\" is a Chat whose message at $at/$index $it." }
        }
    }

    /**
     * Every entry any Chat on [view] shows, whatever its shape. A writer compares it before and
     * after a write, so binding a Chat to entries appended raw can't show a message nobody posted.
     */
    fun chatEntries(view: SurfaceView): Set<JsonElement> = view.components.values
        .filter { it["component"]?.stringOrNull() == CHAT }
        .mapNotNull { chat -> A2uiBindings.pathOf(chat["messages"]) }
        .flatMap { path -> (valueAt(view.data, path) as? JsonArray).orEmpty() }
        .toSet()

    /** What a Chat message holds, each a string. */
    private val MESSAGE_FIELDS = listOf("id", "author", "text", "at")

    /**
     * The problems with the cards a Board [component] binds in [data]: an `href` that isn't an
     * `http:` or `https:` URL string (empty is fine: it clears the link), or a `detail` that isn't a string. Cards not written yet are fine.
     */
    private fun cardErrors(id: String, component: JsonObject, data: JsonObject): List<String> {
        if (component["component"]?.stringOrNull() != BOARD) return emptyList()
        val path = A2uiBindings.pathOf(component["cards"]) ?: return emptyList()
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
                .map { "Component \"$id\" is a $type whose $it isn't a data binding ({\"@path\": \"/…\"})." }
        }
    }

    /** A2UI's `DataBinding`: `{"@path": "/…"}`, an absolute pointer into the data model. */
    private fun isBinding(property: JsonElement?): Boolean = A2uiBindings.pathOf(property)?.startsWith("/") == true

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
