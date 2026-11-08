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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
                "Pass the returned cursor to the next call to keep reading from where this one stopped. " +
                "A record is returned with the claim token this session holds it under; one whose claim a person " +
                "reassigned to this session, from another that left, arrives here too, under the reassign's token. " +
                "A pairing surface the host opened, such as a join request, is refused: the host handles its records.",
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
                "Returns {records, cursor}. Pass a cursor a previous call returned as since to list only " +
                "the records after it. A pairing surface the host opened is refused, as bee_wait_action refuses it.",
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
    ): CallToolResult = refusing {
        val surfaceId = request.requiredString("surfaceId")
        val cursor = request.optionalString("cursor")
        val timeout = request.timeout()
        val progressToken = request.params.meta?.progressToken
        val result = coroutineScope {
            val ticker = progressToken?.let { launch { sendProgress(it, surfaceId, timeout, progressEvery) } }
            try {
                source.next(surfaceId, cursor, timeout)
            } finally {
                ticker?.cancel()
            }
        }
        CallToolResult(content = listOf(TextContent(result.toJson().toString())), structuredContent = result.toJson())
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

    private fun listActions(request: CallToolRequest, source: ActionSource): CallToolResult = refusing {
        val page = source.list(request.requiredString("surfaceId"), request.optionalString("since"))
        val json = buildJsonObject {
            put("records", JsonArray(page.records))
            put("cursor", page.cursor)
        }
        CallToolResult(content = listOf(TextContent(json.toString())), structuredContent = json)
    }

    private fun WaitResult.toJson(): JsonObject = buildJsonObject {
        record?.let { put("record", it) }
        put("timedOut", timedOut)
        put("cursor", cursor)
    }

    /** A malformed argument, refused back to the caller as a tool error that names it. */
    private class Refusal(message: String) : Exception(message)

    private inline fun refusing(block: () -> CallToolResult): CallToolResult = try {
        block()
    } catch (e: Refusal) {
        CallToolResult(content = listOf(TextContent(e.message.orEmpty())), isError = true)
    } catch (e: BridgeRefusal) {
        // The source's own refusal, such as a retirement met mid-wait: the agent needs its words.
        CallToolResult(content = listOf(TextContent(e.message.orEmpty())), isError = true)
    }

    /** A string argument, or `null` when it's absent or JSON `null`. Any other type is refused. */
    private fun CallToolRequest.optionalString(key: String): String? {
        val value = arguments?.get(key)
        return when {
            value == null || value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            else -> throw Refusal("$key must be a string; got $value.")
        }
    }

    private fun CallToolRequest.requiredString(key: String): String =
        optionalString(key) ?: throw Refusal("$key is required.")

    /**
     * `timeoutSeconds`: a JSON integer from 1 to 60, [DEFAULT_TIMEOUT] when absent. A string such as
     * `"30"` is refused rather than read as a number, because the schema says integer.
     */
    private fun CallToolRequest.timeout(): Duration {
        val value = arguments?.get("timeoutSeconds")
        if (value == null || value is JsonNull) return DEFAULT_TIMEOUT
        val bound = "timeoutSeconds must be a whole number from 1 to ${MAX_TIMEOUT.inWholeSeconds}"
        val seconds = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw Refusal("$bound; got $value.")
        if (seconds !in 1..MAX_TIMEOUT.inWholeSeconds) throw Refusal("$bound; got $seconds.")
        return seconds.seconds
    }
}
