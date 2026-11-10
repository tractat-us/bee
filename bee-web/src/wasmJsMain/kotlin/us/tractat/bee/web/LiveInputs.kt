package us.tractat.bee.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import us.tractat.bee.a2ui.A2uiBindings
import us.tractat.bee.a2ui.SurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One `subscribeData` subscription (bee-notions/src/renderer-host.js). */
external interface DataSubscription : JsAny {
    fun unsubscribe()
}

/** bee-notions' live-typing hooks: web_core's `DataModel.subscribe`, and `TextField` focus. Only strings cross. */
@JsModule("@bee/notions")
external object NotionsLive : JsAny {
    /** Calls [handler] with [path]'s value as JSON on each change; null if [surfaceId] isn't shown. */
    fun subscribeData(rootElement: Element, surfaceId: String, path: String, handler: (String) -> Unit): DataSubscription?

    /** Calls [handler] with `{surfaceId, componentId, focused}` as focus enters or leaves a `TextField`. */
    fun onTextFieldFocus(rootElement: Element, handler: (String) -> Unit)
}

/**
 * Live shared typing (spec §5.3). In A2UI v0.9 a bound `TextField` writes only the renderer's local
 * data model, so this subscribes to that model for every path a `TextField` binds, and writes each
 * change into the replica through `updateData`, debounced to [debounce].
 *
 * - **The echo is ignored.** A change made while [BoardDriver] renders is the driver's own, never
 *   the person's, so it is not written back. Without that, a page shown a conflict's winner would
 *   write it, and clear the conflict for everyone.
 * - **A path being typed in is held.** While a field is focused, or has an edit not yet written,
 *   its path is held: the driver keeps showing the person's own value there, and remote changes
 *   wait until they leave it. [releases] ticks each time a path stops being held, so the driver
 *   renders what waited.
 *
 * An `obscured` field is never tracked: it holds a secret, which must not reach the board as it
 * is typed (§5.7). Typing leaves the board's value at its path as it was, so [BoardDriver]'s diff
 * doesn't resend it, and the typed value stays until a writer changes the board's.
 *
 * Only absolute bindings are tracked (`{"@path": "/…"}`). A `TextField` inside a list template binds
 * a relative path, and v1 has none; one that turns up is reported through [onProblem], as is a
 * surface the renderer can't be watched on, so a field that loses live typing or its hold says so.
 *
 * While any debounced write is outstanding, [root] carries a `data-pending` attribute.
 *
 * Everything here runs on the page's one JS thread, so its state needs no lock.
 */
class LiveInputs(
    private val root: Element,
    private val client: BoardClient,
    private val author: ReplicaId,
    private val scope: CoroutineScope,
    private val onProblem: (String) -> Unit,
    private val debounce: Duration = 150.milliseconds,
) {
    private data class Bound(val surfaceId: String, val path: String)

    private val subscriptions = mutableMapOf<Bound, DataSubscription>()
    private val fieldPaths = mutableMapOf<Pair<String, String>, String>()
    private val focused = mutableSetOf<Bound>()
    private val pending = mutableMapOf<Bound, Job>()
    private val local = mutableMapOf<Bound, JsonElement>()
    private var rendering = false
    private val _releases = MutableStateFlow(0)

    /** Ticks each time a path stops being held. */
    val releases: StateFlow<Int> = _releases.asStateFlow()

    init {
        NotionsLive.onTextFieldFocus(root) { json -> onFocus(Json.parseToJsonElement(json).jsonObject) }
        showPending()
    }

    /** Runs [render], the driver's own write into the renderer; changes it causes are not the person's. */
    fun <T> whileRendering(render: () -> T): T {
        rendering = true
        try {
            return render()
        } finally {
            rendering = false
        }
    }

    /**
     * The paths of surface [surfaceId] held now, each with the value the person left there, or
     * null for a field focused but not yet edited.
     */
    fun held(surfaceId: String): Map<String, JsonElement?> =
        (focused + pending.keys).filter { it.surfaceId == surfaceId }.associate { it.path to local[it] }

    /**
     * Subscribes to every path a `TextField` in [view] binds, after the driver rendered it. A path
     * already subscribed on the surface's current data model is left alone.
     */
    fun track(view: SurfaceView) {
        fieldPaths.keys.removeAll { it.first == view.id }
        view.components.forEach { (componentId, component) ->
            val path = boundPath(component) ?: return@forEach
            if (path.startsWith("/")) {
                fieldPaths[view.id to componentId] = path
            } else {
                onProblem("Field $componentId on ${view.id} binds the relative path $path; live typing doesn't follow it.")
            }
        }
        val paths = fieldPaths.filterKeys { it.first == view.id }.values.toSet()
        subscriptions.keys.filter { it.surfaceId == view.id && it.path !in paths }.forEach { unsubscribe(it) }
        paths.map { Bound(view.id, it) }.filter { it !in subscriptions }.forEach { bound ->
            val subscription = NotionsLive.subscribeData(root, bound.surfaceId, bound.path) { json -> onChange(bound, json) }
            if (subscription == null) {
                onProblem("Live typing is off for ${bound.path} on ${bound.surfaceId}: the renderer shows no such surface.")
            } else {
                subscriptions[bound] = subscription
            }
        }
    }

    /**
     * Drops surface [surfaceId]'s subscriptions: the renderer is about to delete or recreate it,
     * and a recreated surface has a new data model. An edit not yet written still lands.
     */
    fun forget(surfaceId: String) {
        subscriptions.keys.filter { it.surfaceId == surfaceId }.forEach { unsubscribe(it) }
        if (focused.removeAll { it.surfaceId == surfaceId }) _releases.value++
    }

    private fun unsubscribe(bound: Bound) {
        subscriptions.remove(bound)?.unsubscribe()
    }

    private fun onChange(bound: Bound, json: String) {
        if (rendering) return
        val value = Json.parseToJsonElement(json)
        local[bound] = value
        pending.remove(bound)?.cancel()
        pending[bound] = scope.launch {
            delay(debounce)
            write(bound, value)
            if (pending[bound] === coroutineContext[Job]) {
                pending.remove(bound)
                showPending()
                release(bound)
            }
        }
        showPending()
    }

    private fun showPending() {
        if (pending.isEmpty()) root.removeAttribute("data-pending") else root.setAttribute("data-pending", "${pending.size}")
    }

    private suspend fun write(bound: Bound, value: JsonElement) {
        try {
            client.mutate { it.updateData(author, bound.surfaceId, bound.path, value) }
        } catch (refused: IllegalArgumentException) {
            // updateData refuses a write to a surface the board doesn't hold, or one that would
            // change the path's type. The edit stays on the page only, and the person is told.
            onProblem("Couldn't save ${bound.path} on ${bound.surfaceId}: ${refused.message}")
        }
    }

    private fun onFocus(event: JsonObject) {
        event["error"]?.let { return onProblem("The hold is off for a field: ${it.jsonPrimitive.content}") }
        val surfaceId = event.getValue("surfaceId").jsonPrimitive.content
        val path = fieldPaths[surfaceId to event.getValue("componentId").jsonPrimitive.content] ?: return
        val bound = Bound(surfaceId, path)
        if (event.getValue("focused").jsonPrimitive.boolean) {
            focused += bound
        } else if (focused.remove(bound)) {
            release(bound)
        }
    }

    /** Lets the driver render [bound] again, once nothing holds it. */
    private fun release(bound: Bound) {
        if (bound in focused || bound in pending) return
        local.remove(bound)
        _releases.value++
    }

    private companion object {
        /**
         * The data path a `TextField`'s `value` binds, absolute or not, or null if it binds none or
         * is [obscured][isObscured].
         */
        fun boundPath(component: JsonObject): String? {
            if ((component["component"] as? JsonPrimitive)?.content != "TextField" || component.isObscured()) return null
            return A2uiBindings.pathOf(component["value"])
        }

        /**
         * Whether this is A2UI's `obscured` input, the catalog's field for a secret, such as a
         * pairing code (§5.7). It never syncs live: what is typed stays in this page's renderer,
         * and leaves it only inside the context of an action that reads its path. Live typing
         * would write each keystroke to every replica.
         */
        fun JsonObject.isObscured(): Boolean = (this["variant"] as? JsonPrimitive)?.content == "obscured"
    }
}
