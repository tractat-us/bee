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
}

/**
 * Drives the stock A2UI Lit renderer from Kotlin. The renderer never sees a CRDT: it is
 * handed A2UI messages and hands back user actions.
 *
 * It tracks which surface ids exist, so creating one that is already open becomes
 * delete + create; the stock processor would refuse it as an integrity error.
 */
class A2uiRenderer(root: Element) {
    private val host = Notions.createRendererHost(root)
    private val open = mutableSetOf<String>()
    private val received = Channel<A2uiAction>(Channel.UNLIMITED)

    /** User actions, in the order the renderer reported them. Collect it once. */
    val actions: Flow<A2uiAction> = received.receiveAsFlow()

    init {
        host.onAction { json -> received.trySend(A2uiJson.decodeAction(json)) }
    }

    /**
     * Applies [messages] in order. Returns the errors the renderer reported, as its
     * RendererErrorMessage objects, empty when it reported none.
     */
    fun render(messages: List<A2uiMessage>): List<JsonObject> {
        val sent = messages.flatMap(::withReopen)
        val errors = host.process(A2uiJson.encode(sent))
        return Json.parseToJsonElement(errors).jsonArray.map { it.jsonObject }
    }

    private fun withReopen(message: A2uiMessage): List<A2uiMessage> = when (message) {
        is A2uiMessage.CreateSurface ->
            if (open.add(message.surfaceId)) listOf(message) else listOf(A2uiMessage.DeleteSurface(message.surfaceId), message)
        is A2uiMessage.DeleteSurface -> listOf(message).also { open.remove(message.surfaceId) }
        else -> listOf(message)
    }
}
