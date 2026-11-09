package us.tractat.bee.web

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import us.tractat.bee.a2ui.A2uiAction
import us.tractat.bee.a2ui.A2uiJson
import us.tractat.bee.a2ui.A2uiMessage

/** bee-notions' renderer host (bee-notions/src/renderer-host.js). Only strings cross. */
external interface RendererHost : JsAny {
    /** Applies a JSON array of A2UI messages; returns a JSON array of the errors reported. */
    fun process(messagesJson: String): String

    /** Calls [handler] with each user action, as the JSON of web_core's ActionPayload. */
    fun onAction(handler: (String) -> Unit)
}

@JsModule("@bee/notions")
external object Notions : JsAny {
    /** Mounts a renderer under [rootElement], with the Notions catalog. */
    fun createRendererHost(rootElement: Element): RendererHost

    /** Mounts a renderer under [rootElement], with the Notions catalog; [place] puts each new surface's element. */
    fun createPlacedRendererHost(rootElement: Element, place: (surfaceId: String, element: Element) -> Unit): RendererHost
}

/**
 * Drives the stock A2UI Lit renderer from Kotlin. The renderer never sees a CRDT: it is
 * handed A2UI messages and hands back user actions.
 *
 * Each new surface's `<a2ui-surface>` goes where [place][A2uiRenderer] puts it, under the root:
 * appended to the root unless the page says otherwise (the board puts each in its card).
 *
 * It tracks which surface ids exist, so creating one that is already open becomes
 * delete + create; the stock processor would refuse it as an integrity error.
 */
class A2uiRenderer(root: Element, place: (surfaceId: String, element: Element) -> Unit = { _, element -> root.appendChild(element) }) {
    private val host = Notions.createPlacedRendererHost(root, place)
    private val open = mutableSetOf<String>()

    /**
     * User actions, in the order the renderer reports them, each with what [witness] read for it.
     * [witness] runs as the renderer reports the action, synchronously with the person's act, so
     * nothing has rendered since: it reads what was on screen. Ask before the person can act, since
     * an action reaches only the flows asked for before it, and collect each flow once.
     */
    fun <T> actions(witness: (A2uiAction) -> T): Flow<Witnessed<T>> {
        val received = Channel<Witnessed<T>>(Channel.UNLIMITED)
        host.onAction { json ->
            val action = A2uiJson.decodeAction(json)
            received.trySend(Witnessed(action, witness(action)))
        }
        return received.receiveAsFlow()
    }

    /** A user [action], with what a witness read for it as the person acted ([seen]). */
    data class Witnessed<out T>(val action: A2uiAction, val seen: T)

    /**
     * Applies [messages] in order.
     *
     * @throws A2uiRenderException if the renderer reported any error. The processor applies
     *   every message it can before reporting, so the messages before and after a bad one
     *   still land; the errors are never dropped.
     */
    fun render(messages: List<A2uiMessage>) {
        val sent = messages.flatMap(::withReopen)
        val errors = Json.parseToJsonElement(host.process(A2uiJson.encode(sent))).jsonArray.map { it.jsonObject }
        if (errors.isNotEmpty()) throw A2uiRenderException(errors)
    }

    private fun withReopen(message: A2uiMessage): List<A2uiMessage> = when (message) {
        is A2uiMessage.CreateSurface ->
            if (open.add(message.surfaceId)) listOf(message) else listOf(A2uiMessage.DeleteSurface(message.surfaceId), message)
        is A2uiMessage.DeleteSurface -> listOf(message).also { open.remove(message.surfaceId) }
        else -> listOf(message)
    }
}

/** The renderer refused some of what it was given. [errors] are its RendererErrorMessage objects. */
class A2uiRenderException(val errors: List<JsonObject>) : IllegalStateException("The A2UI renderer reported errors: $errors")
