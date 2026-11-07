package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One surface as the renderer is shown it: a single value per path, with the conflicts beside it.
 *
 * @property components each component's JSON, keyed by component id
 * @property data the data model, one winner per leaf
 * @property conflicts every leaf holding concurrent values, keyed by its JSON Pointer into [data],
 *   listing all of them in [SurfaceFormat.VALUE_ORDER], winner last
 */
data class SurfaceView(
    val id: String,
    val catalogId: String,
    val components: Map<String, JsonObject>,
    val data: JsonObject,
    val conflicts: Map<String, List<JsonElement>>,
) {
    /**
     * The data model the renderer holds: [data], plus the conflicts published at
     * `/_bee/conflicts/<escaped pointer>` (spec §5.3). `_bee` is absent when nothing conflicts.
     */
    fun dataModel(): JsonObject {
        if (conflicts.isEmpty()) return data
        val published = JsonObject(
            conflicts.keys.sorted().associate { SurfaceFormat.escapeSegment(it) to JsonArray(conflicts.getValue(it)) },
        )
        return JsonObject(data + (SurfaceFormat.RESERVED_DATA_KEY to JsonObject(mapOf("conflicts" to published))))
    }
}
