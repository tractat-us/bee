package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.mcp.BeeTools
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A session's first `bee_board`, called the moment it has connected over `/mcp`, shows the board
 * the host already holds, not the empty replica its bridge started with (bee#240).
 *
 * The window is the bridge's first-contact sync with the host's relay, tens of milliseconds on
 * loopback, so one session may miss it by luck: the test opens [SESSIONS] in turn and counts the
 * first reads that came back without the surface. bee-core's `BoardClientSyncedTest` forces the
 * window deterministically; this pins the whole path, MCP to replica.
 *
 * A first write is decided against the same board: a session that opens a surface already there,
 * the moment it connects, is refused, never given a second, concurrent open of it.
 */
class McpHttpFirstReadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-mcp-first-read-")
    private val http = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aSessionsFirstBoardReadShowsTheSurfacesAlreadyOpen() = runBlocking {
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir), PeerAddress.DIRECT)
        try {
            val opener = connect(host, "ada")
            val opened = opener.callTool(BeeTools.OPEN, openArgs(SURFACE))
            check(opened.isError != true) { "rig: the open failed: ${opened.content.filterIsInstance<TextContent>().map { it.text }}" }
            withTimeout(TIMEOUT) { host.state.first { SURFACE in it.openSurfaces() } }
            val missed = (1..SESSIONS).map { n ->
                val late = connect(host, "late$n")
                val board = Json.parseToJsonElement(text(late.callTool(BeeTools.BOARD, emptyMap())))
                late.close()
                board.jsonObject.getValue("open").jsonArray.none { it.jsonObject.getValue("surfaceId").jsonPrimitive.content == SURFACE }
            }.count { it }
            // Refused for what is on the board, not for a board that hasn't arrived.
            val answers = (1..SESSIONS).map { n ->
                val late = connect(host, "eager$n")
                val again = late.callTool(BeeTools.OPEN, openArgs(SURFACE, data = false))
                late.close()
                again
            }
            val notRefusedAsThere = answers.filterNot { it.isError == true && "already on the board" in text(it) }.map(::text)
            assertAll(
                { assertEquals(0, missed, "of $SESSIONS sessions' first bee_board, these came back without $SURFACE") },
                {
                    assertEquals(
                        emptyList(),
                        notRefusedAsThere,
                        "every session's first bee_surface_open of $SURFACE is refused as already on the board",
                    )
                },
            )
            opener.close()
        } finally {
            host.close()
        }
    }

    private suspend fun connect(host: BeeHost, name: String): Client =
        Client(Implementation("claude-code", "2.1.319")).apply {
            connect(StreamableHttpClientTransport(http, "http://127.0.0.1:${host.port}${BeeHost.MCP_PATH}?name=$name"))
        }

    private fun text(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().single().text

    /** [id]'s open, carrying the few MiB of [data] that slow the first-contact sync, or none. */
    private fun openArgs(id: String, data: Boolean = true) = mapOf(
        "surfaceId" to id,
        "title" to "Surface $id",
        "components" to JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "root")
                    put("component", "Text")
                    put("text", "Hello")
                },
            ),
        ),
        // A board worth a few MiB, as a board in use for a while is: its first-contact sync takes a while.
        "data" to buildJsonObject { if (data) put("notes", "n".repeat(BOARD_BYTES)) },
    )

    private companion object {
        val TIMEOUT = 20.seconds
        const val SURFACE = "already-open"
        const val SESSIONS = 10
        const val BOARD_BYTES = 3 * 1024 * 1024
    }
}
