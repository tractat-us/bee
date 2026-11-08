package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import us.tractat.bee.a2ui.A2uiAction
import us.tractat.bee.a2ui.A2uiMessage
import us.tractat.bee.a2ui.NotionActions
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.SurfaceView
import us.tractat.bee.a2ui.diff
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

/**
 * The in-page A2UI server (spec §5.3): it shows the board's open surfaces through the stock
 * renderer, and turns each action into an action record.
 *
 * - **It diffs; it never applies deltas (A14).** On every board state it projects each open surface
 *   and renders `diff(lastRendered, view)`, so a skipped `Quilter.state` emission loses nothing.
 * - **It validates before it renders (A11).** A surface that fails [SurfaceValidator], or can't be
 *   projected at all, is shown as a placeholder `<div>` naming its errors, never to the renderer.
 * - **It holds what the person is typing in.** A path [LiveInputs] holds shows the person's own
 *   value, and remote changes to it render once it's released.
 * - **A record's basis is captured inside the transform** that appends it, so it names exactly the
 *   state the record joins (`BasisTest.basisCapturedAtActionTimeIgnoresLaterMerges`).
 *
 * @param root the renderer's root element, where placeholders go too
 * @param author this page load's CRDT author
 * @param actor who acts on this page
 * @param onProblem shows a problem to the person: a renderer error, or a record that couldn't be written
 */
@Suppress("LongParameterList")
class BoardDriver(
    private val client: BoardClient,
    private val renderer: A2uiRenderer,
    private val root: Element,
    private val author: ReplicaId,
    private val actor: PeerIdentity,
    private val inputs: LiveInputs,
    private val clock: () -> Instant,
    private val onProblem: (String) -> Unit,
) {
    /** What the renderer shows for each surface: the last view rendered, held paths included. */
    private val rendered = mutableMapOf<String, SurfaceView>()
    private val placeholders = mutableMapOf<String, Element>()

    /** Renders the board as it changes, and records every action, until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { combine(client.state, inputs.releases) { state, _ -> state }.collect(::show) }
        launch { renderer.actions.collect(::record) }
    }

    private fun show(state: BoardState) {
        val open = state.openSurfaces()
        (rendered.keys + placeholders.keys).filter { it !in open }.forEach { id ->
            render(id, diff(rendered.remove(id), null))
            removePlaceholder(id)
        }
        open.forEach { id -> show(id, requireNotNull(state.surfaceDoc(id))) }
    }

    private fun show(id: String, doc: JsonCrdt) {
        val view = try {
            doc.toSurfaceView(id)
        } catch (unreadable: IllegalArgumentException) {
            return showPlaceholder(id, listOf("Surface $id can't be read: ${unreadable.message}"))
        }
        val errors = SurfaceValidator.errors(view)
        if (errors.isNotEmpty()) return showPlaceholder(id, errors)
        removePlaceholder(id)
        val held = inputs.held(id)
        val shown = view.holding(held, rendered[id])
        // The renderer already shows a held path's value: it's the person's own.
        val messages = diff(rendered[id], shown).filterNot { it is A2uiMessage.UpdateDataModel && it.path in held }
        rendered[id] = shown
        if (messages.isEmpty()) return
        render(id, messages)
        inputs.track(shown)
    }

    private fun render(id: String, messages: List<A2uiMessage>) {
        if (messages.any { it is A2uiMessage.CreateSurface || it is A2uiMessage.DeleteSurface }) inputs.forget(id)
        try {
            inputs.whileRendering { renderer.render(messages) }
        } catch (refused: A2uiRenderException) {
            // The processor applied every message it could; the rest are shown, never dropped.
            onProblem("The renderer refused part of surface $id: ${refused.errors}")
        }
    }

    private fun showPlaceholder(id: String, errors: List<String>) {
        rendered.remove(id)?.let { render(id, diff(it, null)) }
        val placeholder = placeholders.getOrPut(id) {
            document.createElement("div").also {
                it.className = "bee-invalid-surface"
                it.setAttribute("data-surface-id", id)
                root.appendChild(it)
            }
        }
        placeholder.textContent = "Surface $id can't be shown: ${errors.joinToString(" ")}"
    }

    private fun removePlaceholder(id: String) {
        placeholders.remove(id)?.remove()
    }

    /**
     * Appends [action] as a record, after the data write a Notions action makes ([NotionActions]):
     * a `Poll` answer writes this actor's answer, a `Board` drop the card's column, each from the
     * register just read (A13), in the same transform as the record. An action on a surface the
     * board doesn't hold is refused by `Basis.capture`, and an answer or drop naming nothing the
     * surface holds by [NotionActions.edit]; the person is told, and it's never dropped quietly.
     */
    private suspend fun record(action: A2uiAction) {
        try {
            client.mutate { state ->
                val record = ActionRecord(
                    surfaceId = action.surfaceId,
                    componentId = action.sourceComponentId,
                    actor = actor,
                    name = action.name,
                    context = action.context,
                    basis = Basis.capture(state, action.surfaceId),
                    at = clock(),
                )
                val view = requireNotNull(state.surfaceDoc(action.surfaceId)).toSurfaceView(action.surfaceId)
                val written = NotionActions.edit(view, action, actor.toString())
                    ?.let { state.updateData(author, action.surfaceId, it.path, it.value) }
                    ?: return@mutate state.appendRecord(author, record)
                val recorded = state.piece(written).appendRecord(author, record)
                Patch(written.delta.piece(recorded.delta))
            }
        } catch (refused: IllegalArgumentException) {
            onProblem("Couldn't record ${action.name} on ${action.surfaceId}: ${refused.message}")
        }
    }
}

/**
 * This view with each [held] path showing what the person left there, or, for a field focused and
 * not yet edited, what [shown] had there. Remote values for those paths wait for their release.
 */
private fun SurfaceView.holding(held: Map<String, JsonElement?>, shown: SurfaceView?): SurfaceView {
    if (held.isEmpty() || shown == null) return this
    val data = held.entries.fold(data) { model, (path, value) ->
        val segments = SurfaceFormat.segments(path)
        model.replacedAt(segments, value ?: shown.data.valueAt(segments))
    }
    return copy(data = data)
}

private fun JsonObject.valueAt(segments: List<String>): JsonElement? =
    segments.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

/** This object with the value at [segments] set to [value], or removed when [value] is null. */
private fun JsonObject.replacedAt(segments: List<String>, value: JsonElement?): JsonObject {
    if (segments.isEmpty()) return this
    val key = segments.first()
    val next = if (segments.size == 1) {
        value
    } else {
        ((this[key] as? JsonObject) ?: JsonObject(emptyMap())).replacedAt(segments.drop(1), value)
    }
    return JsonObject(if (next == null) this - key else this + (key to next))
}
