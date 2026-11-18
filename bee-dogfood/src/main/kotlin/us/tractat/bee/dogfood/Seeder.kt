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
 * A session that has just joined reads its own replica of the board, which may not have synced
 * with the host yet: a read of it can miss a surface that is there, and a write decided against it
 * could open a second, concurrent copy of one. So before it decides anything, the seeder reads
 * `bee_board` every [retryAfter] until it says the replica has synced (bee's `BoardClient.synced`,
 * a workaround for tractat-us/kuilt#3142), for at most [syncWait]. A replica that hasn't synced by
 * then fails the seed with [BoardNotSynced], before any write. Once synced, a surface missing from
 * the board isn't there, so it opens at once. An open refused as already there, because another
 * session opened it meanwhile, reads the board again after [retryAfter] and decides afresh, up to
 * [attempts] times.
 */
class Seeder(
    private val tools: BoardTools,
    private val syncWait: Duration = 30.seconds,
    private val retryAfter: Duration = 250.milliseconds,
    private val attempts: Int = 40,
) {
    /** @throws BoardNotSynced if the session's replica doesn't sync within [syncWait]: nothing is written */
    suspend fun seed(surfaces: List<SeedSurface>): Map<String, Outcome> {
        awaitSynced()
        return surfaces.associate { it.id to draw(it) }
    }

    /**
     * Reads the board until it says it has synced, for at most [syncWait]. Each `bee_board` waits a
     * moment for the sync host-side, then answers `"synced": false` if it hasn't come. A replica
     * that has synced stays so, so every later read is of the board the host holds.
     */
    private suspend fun awaitSynced() {
        withTimeoutOrNull(syncWait) {
            while (tools.call(BOARD, emptyMap())[SYNCED] == JsonPrimitive(false)) delay(retryAfter)
        } ?: throw BoardNotSynced(syncWait)
    }

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

        /** The `bee_board` field that is `false` while the session's replica hasn't synced, and absent once it has. */
        private const val SYNCED = "synced"

        /** What `bee_surface_open` says of a surface already on the board (BeeBridge.open). */
        const val ALREADY = "is already on the board"
    }
}

/**
 * The session's replica hadn't synced with the host within [within], so the seed wrote nothing: a
 * seed decided against a board still missing surfaces could open second copies of them.
 */
class BoardNotSynced(within: Duration) :
    IllegalStateException("The board hadn't arrived from the host within $within, so nothing was seeded; try again shortly.")

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
