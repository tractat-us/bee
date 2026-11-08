package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The bridge's MCP server as a Claude session meets it: the [WhiteboardGuide]'s instructions in
 * `initialize`, its `whiteboard` prompt, and every tool. `main` serves this one, and so do the
 * bridge's tests, so they see what a session sees.
 */
object BeeServer {
    /** A server for [bridge], running its handlers on [handlerContext]. */
    fun create(bridge: BeeBridge, handlerContext: CoroutineContext = Dispatchers.Default): Server {
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
        return server
    }
}
