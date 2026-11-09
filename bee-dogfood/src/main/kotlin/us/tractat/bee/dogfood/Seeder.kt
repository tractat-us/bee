package us.tractat.bee.dogfood

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/** The board's MCP tools, as the seeder calls them: a tool's name and arguments in, its JSON answer out. */
fun interface BoardTools {
    /** @throws IllegalStateException if the tool answers with an error */
    suspend fun call(name: String, arguments: Map<String, JsonElement>): JsonObject
}

/** What a seed did to one surface. */
enum class Outcome {
    /** It wasn't on the board, so the seed opened it. */
    Opened,

    /** It was open with other content, so the seed redrew it. */
    Updated,

    /** It was open with this content already. */
    Unchanged,

    /** Someone closed it: the seed redrew it in place, and it stays in the history strip until reopened. */
    UpdatedClosed,
}

/**
 * Draws [SeedSurface]s on a board through its MCP tools, each whole: one it doesn't find is opened,
 * one it finds is redrawn in place under the same id, so re-seeding never duplicates a surface.
 */
class Seeder(private val tools: BoardTools) {
    suspend fun seed(surfaces: List<SeedSurface>): Map<String, Outcome> {
        val board = tools.call(BOARD, emptyMap())
        val open = board.getValue("open").jsonArray.associate { it.jsonObject.string("surfaceId") to it.jsonObject }
        val closed = board.getValue("closed").jsonArray.map { it.jsonPrimitive.content }.toSet()
        return surfaces.associate { surface ->
            surface.id to when {
                surface.id in open -> if (drawn(open.getValue(surface.id), surface)) Outcome.Unchanged else update(surface, Outcome.Updated)
                surface.id in closed -> update(surface, Outcome.UpdatedClosed)
                else -> open(surface)
            }
        }
    }

    /** Whether [current] already shows every component of [surface] as it would draw it. */
    private fun drawn(current: JsonObject, surface: SeedSurface): Boolean {
        val components = current.getValue("components").jsonArray.associateBy { it.jsonObject.string("id") }
        return surface.components.all { components[it.string("id")] == it }
    }

    private suspend fun open(surface: SeedSurface): Outcome {
        tools.call(
            OPEN,
            mapOf(
                "surfaceId" to JsonPrimitive(surface.id),
                "title" to JsonPrimitive(surface.title),
                "components" to JsonArray(surface.components),
                "data" to surface.data,
            ),
        )
        return Outcome.Opened
    }

    private suspend fun update(surface: SeedSurface, outcome: Outcome): Outcome {
        val data = JsonObject(surface.data.mapKeys { (key, _) -> "/$key" })
        tools.call(
            UPDATE,
            mapOf(
                "surfaceId" to JsonPrimitive(surface.id),
                "components" to JsonArray(surface.components),
                "data" to data,
            ),
        )
        return outcome
    }

    companion object {
        const val BOARD = "bee_board"
        const val OPEN = "bee_surface_open"
        const val UPDATE = "bee_surface_update"
    }
}


/**
 * A session on the board at [host] over MCP Streamable HTTP, as a Claude with nothing installed
 * joins: `POST /mcp?name=<name>`. The host names its identity `<name>-claude`.
 */
class McpBoardTools private constructor(private val http: HttpClient, private val transport: StreamableHttpClientTransport, private val client: Client) :
    BoardTools,
    AutoCloseable {
    override suspend fun call(name: String, arguments: Map<String, JsonElement>): JsonObject {
        val result = client.callTool(name, arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        check(result.isError != true) { "$name was refused: $text" }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Ends the session, so the host departs it at once, then closes the client. */
    suspend fun leave() {
        transport.terminateSession()
        client.close()
        http.close()
    }

    override fun close() = http.close()

    companion object {
        suspend fun join(host: String, name: String): McpBoardTools {
            val http = HttpClient(CIO) { install(SSE) }
            val url = "${host.trimEnd('/')}/mcp?name=${URLEncoder.encode(name, Charsets.UTF_8)}"
            val transport = StreamableHttpClientTransport(http, url)
            val client = Client(Implementation("bee-dogfood", "0"))
            try {
                client.connect(transport)
            } catch (cancelled: CancellationException) {
                http.close()
                throw cancelled
            } catch (failed: Exception) {
                http.close()
                throw IllegalStateException("Couldn't join the board at $url: ${failed.message}", failed)
            }
            return McpBoardTools(http, transport, client)
        }
    }
}
