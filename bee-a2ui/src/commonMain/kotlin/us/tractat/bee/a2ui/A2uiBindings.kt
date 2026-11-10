package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A2UI v1.0's data bindings and function calls, and the upgrade from v0.9.1's spelling (bee#230).
 *
 * v1.0 marks a binding `{"@path": "/…"}` and a function call `{"@call": …, "args": …}`. It reads
 * v0.9.1's `{"path": "/…"}` as a literal object, so a board saved before the move would render its
 * bindings as data. Each component is stored whole, as its canonical JSON, so [upgrade] runs where a
 * stored component is read ([toSurfaceView]), and nothing stored is rewritten.
 */
object A2uiBindings {
    /** The key of a v1.0 `DataBinding`. */
    const val PATH = "@path"

    /** The key of a v1.0 `FunctionCall`. */
    const val CALL = "@call"

    private const val LEGACY_PATH = "path"
    private const val LEGACY_CALL = "call"
    private const val ARGS = "args"
    private const val LEGACY_RETURN_TYPE = "returnType"

    /** A v1.0 binding to [path]. */
    fun binding(path: String): JsonObject = JsonObject(mapOf(PATH to JsonPrimitive(path)))

    /** The path [property] binds, if it is a v1.0 binding; else null. */
    fun pathOf(property: JsonElement?): String? =
        ((property as? JsonObject)?.get(PATH) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * [component] with every v0.9.1 binding and function call in v1.0's spelling.
     *
     * An object whose only key is a string `path` is a binding; one with a string `call` and at most
     * `args` and `returnType` beside it is a function call, and loses `returnType`, which v1.0 keeps
     * in the catalog. Nothing else changes: a ChildList template (`{"componentId", "path"}`) keeps
     * its spelling in v1.0, and an object with any other key was never a binding. Upgrading a v1.0
     * component changes nothing.
     */
    fun upgrade(component: JsonObject): JsonObject = upgradeObject(component)

    private fun upgradeElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> upgradeObject(element)
        is JsonArray -> JsonArray(element.map(::upgradeElement))
        else -> element
    }

    private fun upgradeObject(o: JsonObject): JsonObject = when {
        o.keys == setOf(LEGACY_PATH) && o.isString(LEGACY_PATH) -> JsonObject(mapOf(PATH to o.getValue(LEGACY_PATH)))
        o.isString(LEGACY_CALL) && o.keys.all { it in LEGACY_CALL_KEYS } -> JsonObject(
            buildMap {
                put(CALL, o.getValue(LEGACY_CALL))
                o[ARGS]?.let { put(ARGS, upgradeElement(it)) }
            },
        )
        else -> JsonObject(o.mapValues { (_, value) -> upgradeElement(value) })
    }

    private val LEGACY_CALL_KEYS = setOf(LEGACY_CALL, ARGS, LEGACY_RETURN_TYPE)

    private fun JsonObject.isString(key: String): Boolean = (this[key] as? JsonPrimitive)?.isString == true
}
