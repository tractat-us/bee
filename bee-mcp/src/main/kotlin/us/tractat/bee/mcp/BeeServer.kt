package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import us.tractat.bee.telemetry.BeeTelemetry
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * The bridge's MCP server as a Claude session meets it: the [WhiteboardGuide]'s instructions in
 * `initialize`, its `whiteboard` prompt, the [CheckinGuide]'s `checkin` prompt, and every tool. `main` serves this one, and so do the
 * bridge's tests, so they see what a session sees.
 */
object BeeServer {
    /** A server for [bridge], running its handlers on [handlerContext], each tool call a span in [telemetry] ([recordToolCalls]). */
    fun create(
        bridge: BeeBridge,
        telemetry: BeeTelemetry,
        handlerContext: CoroutineContext = Dispatchers.Default,
        clock: Clock = Clock.System,
    ): Server {
        val server = Server(
            Implementation(name = "bee", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
                handlerCoroutineContext = handlerContext,
            ),
            instructions = WhiteboardGuide.INSTRUCTIONS,
        )
        WaitTool.register(server, BoardActionSource(bridge))
        BeeTools.register(server, bridge)
        WhiteboardGuide.register(server)
        CheckinGuide.register(server)
        server.recordToolCalls(telemetry, clock) { bridge.self }
        return server
    }
}
