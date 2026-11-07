package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `bee_wait_action` and `bee_list_actions`: waiting on a surface without polling, bounded (A10).
 *
 * A wait returns within its timeout, 45 s unless the caller picks another up to 60 s, so it ends
 * before a client's tool-call timeout does. While it holds, it sends a progress notification every
 * `progressEvery` to a caller that asked for progress.
 */
object WaitTool {
    const val WAIT = "bee_wait_action"
    const val LIST = "bee_list_actions"
    val DEFAULT_TIMEOUT: Duration = 45.seconds
    val MAX_TIMEOUT: Duration = 60.seconds

    fun register(server: Server, source: ActionSource, progressEvery: Duration = 10.seconds) {
        require(progressEvery.isPositive()) { "progressEvery must be positive: $progressEvery" }
        server.addTool(
            name = WAIT,
            description = "Waits for the next action record on a surface of the shared board, such as a click. " +
                "Returns {record?, timedOut, cursor} within timeoutSeconds (default 45, at most 60). " +
                "Pass the returned cursor to the next call to keep reading from where this one stopped.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("surfaceId") { put("type", "string") }
                    putJsonObject("cursor") {
                        put("type", "string")
                        put("description", "The cursor a previous call returned; omit to start from the beginning.")
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_TIMEOUT.inWholeSeconds)
                        put("default", DEFAULT_TIMEOUT.inWholeSeconds)
                    }
                },
                required = listOf("surfaceId"),
            ),
        ) { request -> waitAction(request, source, progressEvery) }
        server.addTool(
            name = LIST,
            description = "Lists the action records on a surface of the shared board, without waiting. " +
                "Pass since (a cursor) to list only the records after it.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("surfaceId") { put("type", "string") }
                    putJsonObject("since") { put("type", "string") }
                },
                required = listOf("surfaceId"),
            ),
        ) { request -> listActions(request, source) }
    }

    private suspend fun ClientConnection.waitAction(
        request: CallToolRequest,
        source: ActionSource,
        progressEvery: Duration,
    ): CallToolResult {
        val surfaceId = request.string("surfaceId") ?: return refusal("surfaceId is required.")
        val timeout = when (val seconds = request.arguments?.get("timeoutSeconds")) {
            null -> DEFAULT_TIMEOUT
            else -> seconds.jsonPrimitive.intOrNull?.seconds
                ?: return refusal("timeoutSeconds must be a whole number of seconds: $seconds")
        }
        if (timeout > MAX_TIMEOUT || !timeout.isPositive()) {
            return refusal("timeoutSeconds must be between 1 and ${MAX_TIMEOUT.inWholeSeconds}; got ${timeout.inWholeSeconds}.")
        }
        val progressToken = request.params.meta?.progressToken
        val result = coroutineScope {
            val ticker = progressToken?.let { launch { sendProgress(it, surfaceId, timeout, progressEvery) } }
            try {
                source.next(surfaceId, request.string("cursor"), timeout)
            } finally {
                ticker?.cancel()
            }
        }
        return CallToolResult(content = listOf(TextContent(result.toJson().toString())), structuredContent = result.toJson())
    }

    private suspend fun ClientConnection.sendProgress(
        token: ProgressToken,
        surfaceId: String,
        timeout: Duration,
        every: Duration,
    ) {
        var held = Duration.ZERO
        while (true) {
            delay(every)
            held += every
            notification(
                ProgressNotification(
                    ProgressNotificationParams(
                        progressToken = token,
                        progress = held.inWholeSeconds.toDouble(),
                        total = timeout.inWholeSeconds.toDouble(),
                        message = "Waiting on $surfaceId",
                    ),
                ),
            )
        }
    }

    private fun listActions(request: CallToolRequest, source: ActionSource): CallToolResult {
        val surfaceId = request.string("surfaceId") ?: return refusal("surfaceId is required.")
        val records = buildJsonObject { put("records", JsonArray(source.list(surfaceId, request.string("since")))) }
        return CallToolResult(content = listOf(TextContent(records.toString())), structuredContent = records)
    }

    private fun WaitResult.toJson(): JsonObject = buildJsonObject {
        record?.let { put("record", it) }
        put("timedOut", timedOut)
        put("cursor", cursor)
    }

    private fun CallToolRequest.string(key: String): String? =
        (arguments?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun refusal(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)
}
