package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.BeeTelemetry
import us.tractat.bee.telemetry.Role
import us.tractat.bee.telemetry.TelemetryResource
import us.tractat.bee.telemetry.event
import kotlin.time.Clock

/**
 * The arguments a tool call's span keeps: ids, and only as strings. Every other argument, a
 * surface's components, its data, a status line, is content, and stays out.
 */
private val ID_ARGUMENTS = mapOf("surfaceId" to Attr.SURFACE, "recordId" to "bee.record", "target" to "bee.claim")

/** The longest id a span keeps; a longer one isn't an id. */
private const val MAX_ID_LENGTH = 200

/**
 * Wraps every tool on this server so each call is a `mcp.tool` span: the tool's name, the calling
 * [peer] as a bridge (an in-host HTTP bridge records into the host's telemetry), its id arguments ([ID_ARGUMENTS]), how long it took and how it ended, `ok`, `error` (the
 * tool answered with an error) or the exception's class. Call it after every tool is registered.
 * The handler's result and exceptions pass through untouched.
 */
fun Server.recordToolCalls(telemetry: BeeTelemetry, clock: Clock, peer: () -> PeerIdentity) {
    val wrapped = tools.values.map { registered ->
        RegisteredTool(registered.tool) { request ->
            val start = clock.now()
            val attributes = request.idAttributes() + mapOf(Attr.TOOL to registered.tool.name, Attr.ROLE to Role.BRIDGE, Attr.PEER to peer().toString())
            try {
                registered.handler(this, request).also { result ->
                    val error = if (result.isError == true) "error" else null
                    telemetry.span("mcp.tool", start, clock.now(), attributes + (Attr.OUTCOME to (error ?: "ok")), error)
                }
            } catch (cancelled: CancellationException) {
                telemetry.span("mcp.tool", start, clock.now(), attributes + (Attr.OUTCOME to "cancelled"), "cancelled")
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                val kind = failure::class.simpleName ?: "Throwable"
                telemetry.span("mcp.tool", start, clock.now(), attributes + (Attr.OUTCOME to kind), kind)
                throw failure
            }
        }
    }
    removeTools(wrapped.map { it.tool.name })
    addTools(wrapped)
}

private fun CallToolRequest.idAttributes(): Map<String, String> = ID_ARGUMENTS.mapNotNull { (argument, attribute) ->
    (arguments?.get(argument) as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= MAX_ID_LENGTH }?.let { attribute to it.content }
}.toMap()

/**
 * Records a stdio bridge's link to the host: each connect and disconnect, and each failed dial,
 * its cause as [describe] renders it (the host address's key taken out). Stamps [resource] with
 * the board once [pin] learns it, and with the bridge's identity as `/clear` changes it.
 */
@Suppress("LongParameterList")
fun CoroutineScope.watchBridge(
    board: BoardClient,
    pin: BoardPin,
    identity: StateFlow<PeerIdentity>,
    describe: (Throwable) -> String,
    telemetry: BeeTelemetry,
    resource: TelemetryResource,
) {
    // Undispatched, so each subscribes before this returns and the first connect isn't missed.
    val now = CoroutineStart.UNDISPATCHED
    launch(start = now) { board.online.drop(1).collect { telemetry.event(if (it) "socket.connected" else "socket.disconnected", Attr.PATH to "/ws/board") } }
    launch(start = now) { board.lastFailure.filterNotNull().collect { telemetry.event("socket.dial_failed", Attr.PATH to "/ws/board", Attr.REASON to describe(it)) } }
    launch(start = now) { pin.board.filterNotNull().collect { resource.set(Attr.BOARD, it.value) } }
    launch(start = now) { identity.collect { resource.set(Attr.PEER, it.toString()) } }
}
