package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The A2UI messages that take a renderer showing [prev] to showing [next] (A14).
 *
 * The driver diffs whole views and never applies CRDT deltas, because `Quilter.state` is conflated
 * and a delta can be skipped. `null` means the surface isn't shown: a closed surface diffs to
 * `deleteSurface`, and a new or reopened one to `createSurface`, its components and its whole
 * data model. Otherwise only components whose JSON changed are sent, and each changed data path
 * gets one `updateDataModel` at the deepest object both views share. An array is never diffed
 * inside: when it changes, it's replaced whole.
 *
 * A2UI v0.9.1 has no null value, so an object member holding JSON null is shown as absent, in
 * `createSurface`'s model and in every later diff alike: a leaf set to null diffs to a delete.
 */
fun diff(prev: SurfaceView?, next: SurfaceView?): List<A2uiMessage> = when {
    next == null -> listOfNotNull(prev?.let { A2uiMessage.DeleteSurface(it.id) })
    prev == null -> create(next)
    prev.catalogId != next.catalogId -> listOf(A2uiMessage.DeleteSurface(prev.id)) + create(next)
    else -> {
        require(prev.id == next.id) { "Diffing two different surfaces: ${prev.id} and ${next.id}" }
        changedComponents(prev, next) + dataChanges(next.id, "", shown(prev), shown(next))
    }
}

private fun create(view: SurfaceView): List<A2uiMessage> = listOfNotNull(
    A2uiMessage.CreateSurface(view.id, view.catalogId),
    view.components.takeIf { it.isNotEmpty() }?.let { A2uiMessage.UpdateComponents(view.id, it.values.toList()) },
    A2uiMessage.UpdateDataModel(view.id, ROOT, shown(view)),
)

private fun changedComponents(prev: SurfaceView, next: SurfaceView): List<A2uiMessage> {
    val changed = next.components.filter { (id, component) -> prev.components[id] != component }
    return if (changed.isEmpty()) emptyList() else listOf(A2uiMessage.UpdateComponents(next.id, changed.values.toList()))
}

private fun dataChanges(surfaceId: String, pointer: String, a: JsonElement?, b: JsonElement?): List<A2uiMessage> =
    when {
        a == b -> emptyList()
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).sorted().flatMap { key ->
            dataChanges(surfaceId, "$pointer/${SurfaceFormat.escapeSegment(key)}", a[key], b[key])
        }
        else -> listOf(A2uiMessage.UpdateDataModel(surfaceId, pointer.ifEmpty { ROOT }, b))
    }

/** The data model the renderer is sent: [SurfaceView.dataModel] without null object members. */
private fun shown(view: SurfaceView): JsonObject = withoutNulls(view.dataModel()) as JsonObject

// Only object members drop: an array keeps its nulls, so its indices stay as written.
private fun withoutNulls(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterValues { it != JsonNull }.mapValues { withoutNulls(it.value) })
    is JsonArray -> JsonArray(element.map { if (it == JsonNull) it else withoutNulls(it) })
    else -> element
}

/** A2UI v0.9's path for the whole data model. */
private const val ROOT = "/"
