package us.tractat.bee.dogfood

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One surface the seeder draws, whole: its [components] (one with id `root`) and the [data] keys it
 * writes. A re-seed upserts every component by id and writes every top-level data key, so a
 * surface's content is whatever the last seed drew.
 */
data class SeedSurface(
    val id: String,
    val title: String,
    val components: List<JsonObject>,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(components.any { it["id"] == JsonPrimitive(ROOT) }) { "Surface $id has no \"$ROOT\" component." }
    }

    companion object {
        const val ROOT = "root"
    }
}

/** A2UI v1.0 components from the basic catalog, as the seeder uses them. */
internal object Components {
    /** Body text, whose Markdown the page renders (sanitized), links included. */
    fun text(id: String, markdown: String): JsonObject = buildJsonObject {
        put("id", id)
        put("component", "Text")
        put("text", markdown)
    }

    fun column(id: String, children: List<String>): JsonObject = buildJsonObject {
        put("id", id)
        put("component", "Column")
        put("children", JsonArray(children.map(::JsonPrimitive)))
    }

    fun card(id: String, child: String): JsonObject = buildJsonObject {
        put("id", id)
        put("component", "Card")
        put("child", child)
    }

    fun binding(path: String): JsonElement = buildJsonObject { put("@path", path) }
}
