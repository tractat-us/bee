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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
 *
 * A session that has just joined reads its own replica of the board, which may not have caught up
 * yet: its first `bee_board` can miss a surface that is there. Opening it then isn't always
 * refused. A replica that is behind takes the open, and the board gets a second, concurrent open
 * of the surface (tractat-us/kuilt#3142, which would give a caught-up signal to wait on instead).
 * So before it opens anything, the seeder re-reads the board every [retryAfter] until every
 * surface it draws is there, open or closed, or [settle] runs out: a bound, not a proof. An open
 * that is still refused as already there reads the board again after [retryAfter] and decides
 * afresh, up to [attempts] times.
 */
class Seeder(
    private val tools: BoardTools,
    private val settle: Duration = 2.seconds,
    private val retryAfter: Duration = 250.milliseconds,
    private val attempts: Int = 40,
) {
    suspend fun seed(surfaces: List<SeedSurface>): Map<String, Outcome> {
        awaitOnBoard(surfaces.map { it.id }.toSet())
        return surfaces.associate { it.id to draw(it) }
    }

    /** Waits up to [settle] for every one of [ids] to show on the board (kuilt#3142). */
    private suspend fun awaitOnBoard(ids: Set<String>) {
        withTimeoutOrNull(settle) {
            while (!onBoard(tools.call(BOARD, emptyMap())).containsAll(ids)) delay(retryAfter)
        }
    }

    private fun onBoard(board: JsonObject): Set<String> =
        board.getValue("open").jsonArray.map { it.jsonObject.string("surfaceId") }.toSet() +
            board.getValue("closed").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun draw(surface: SeedSurface): Outcome {
        repeat(attempts - 1) {
            try {
                return drawOnce(surface)
            } catch (refused: ToolRefused) {
                if (refused.tool != OPEN || ALREADY !in refused.reason) throw refused
                delay(retryAfter)
            }
        }
        return drawOnce(surface)
    }

    private suspend fun drawOnce(surface: SeedSurface): Outcome {
        val board = tools.call(BOARD, emptyMap())
        val open = board.getValue("open").jsonArray.associate { it.jsonObject.string("surfaceId") to it.jsonObject }
        val closed = board.getValue("closed").jsonArray.map { it.jsonPrimitive.content }.toSet()
        return when {
            surface.id in open -> if (drawn(open.getValue(surface.id), surface)) Outcome.Unchanged else update(surface, Outcome.Updated)
            surface.id in closed -> update(surface, Outcome.UpdatedClosed)
            else -> open(surface)
        }
    }

    /**
     * Whether [current] already shows every component of [surface] as it would draw it, and holds
     * every data key it writes as it would write it: a card dragged on the board changes only data.
     */
    private fun drawn(current: JsonObject, surface: SeedSurface): Boolean {
        val components = current.getValue("components").jsonArray.associateBy { it.jsonObject.string("id") }
        val data = current.obj("data")
        return surface.components.all { components[it.string("id")] == it } &&
            surface.data.all { (key, value) -> data[key] == value }
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

        /** What `bee_surface_open` says of a surface already on the board (BeeBridge.open). */
        const val ALREADY = "is already on the board"
    }
}

/** A tool's error answer: [tool] refused, saying [reason]. */
class ToolRefused(val tool: String, val reason: String) : IllegalStateException("$tool was refused: $reason")


/**
 * A session on the board at [host] over MCP Streamable HTTP, as a Claude with nothing installed
 * joins: `POST /mcp?name=<name>`. Its client, `bee-dogfood`, is no agent tool the host knows, so the
 * host names its identity `<name>-agent`.
 */
class McpBoardTools private constructor(private val http: HttpClient, private val transport: StreamableHttpClientTransport, private val client: Client) :
    BoardTools {
    override suspend fun call(name: String, arguments: Map<String, JsonElement>): JsonObject {
        val result = client.callTool(name, arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (result.isError == true) throw ToolRefused(name, text)
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Ends the session, so the host departs it at once, then closes the client. */
    suspend fun leave() {
        transport.terminateSession()
        client.close()
        http.close()
    }

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
