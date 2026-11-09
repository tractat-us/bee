package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry

/**
 * The bridge's board, record and claim tools (spec §5.2; the wait tools are [WaitTool]).
 *
 * Every tool that writes to the board says so first: its description starts "Puts … on the
 * shared board, visible to everyone attached", so sharing is always an explicit act (§9).
 */
object BeeTools {
    const val BOARD = "bee_board"
    const val OPEN = "bee_surface_open"
    const val UPDATE = "bee_surface_update"
    const val CLOSE = "bee_surface_close"
    const val CLEAR = "bee_board_clear"
    const val DONE = "bee_action_done"
    const val CLAIM = "bee_claim"
    const val RELEASE = "bee_release"
    const val PRESENCE = "bee_presence"

    /** The tools that write to the board, each described as sharing. */
    val WRITING: Set<String> = setOf(OPEN, UPDATE, CLOSE, CLEAR, DONE, CLAIM, RELEASE, PRESENCE)

    /** The phrase every writing tool's description ends its first clause with. */
    const val SHARED = "on the shared board, visible to everyone attached"

    fun register(server: Server, bridge: BeeBridge) {
        server.addTool(
            name = BOARD,
            description = "Reads the shared board: the open surfaces with their components and data, the closed " +
                "surface ids (newest first), who is attached, each claim the host has mirrored, and whether this " +
                "session's claims are online. You is this session's own identity. Attached lists each peer once: " +
                "agents, and the people and room screens with the board open, leaving out any that departed. A person's lastActedAt is when their page loaded. A claim a person " +
                "reassigned to this session shows here as held by it; the record itself arrives through " +
                "bee_wait_action, on its surface or board-wide, with its token.",
            inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
        ) { _ ->
            bridge.touch()
            answer(board(bridge.board.value, bridge.self, bridge.roster().values, bridge.claimsOnline))
        }
        server.addTool(
            name = OPEN,
            description = "Puts a new surface $SHARED. Takes surfaceId, title, components (A2UI v0.9 components " +
                "from the Notions catalog, one with id \"root\"), an optional data object, and an optional append, " +
                "which maps a JSON Pointer to the entries appended there, each its own entry a vote can name. A " +
                "malformed surface is refused, naming the bad id or type, and the board is unchanged. Notions adds " +
                "Poll, which binds question, options and answers by {\"path\": \"/…\"}: question revisions and " +
                "options go in append, never data (options as strings or {id, label}), and answers is an object " +
                "keyed by peer, which each answer writes. It also adds " +
                "Board, which binds columns and cards, each {items: {<id>: …}, order: [<id>]}, a column {title, " +
                "owner?} and a card {label, column}; a drop moves a card to another column. Each answer or drop is " +
                "also an action record.",
            inputSchema = schema(required = listOf("surfaceId", "title", "components")) {
                putJsonObject("surfaceId") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("components") { put("type", "array"); putJsonObject("items") { put("type", "object") } }
                putJsonObject("data") { put("type", "object") }
                appendProperty()
            },
        ) { request ->
            tool {
                val id = request.string("surfaceId")
                bridge.open(id, request.string("title"), request.objects("components"), request.objectOrEmpty("data"), request.appends())
                answer(buildJsonObject { put("opened", id) })
            }
        }
        server.addTool(
            name = UPDATE,
            description = "Puts changes to a surface $SHARED: components upserted by id, data written at " +
                "JSON Pointers (data maps each pointer to its value), and entries appended (append maps each pointer " +
                "to an array of new entries). An appended entry, like a Poll's option, is never rewritten. Refused, " +
                "with the board unchanged, if the result would be malformed.",
            inputSchema = schema(required = listOf("surfaceId")) {
                putJsonObject("surfaceId") { put("type", "string") }
                putJsonObject("components") { put("type", "array"); putJsonObject("items") { put("type", "object") } }
                putJsonObject("data") { put("type", "object"); put("description", "Pointer to value, e.g. {\"/count\": 2}.") }
                appendProperty()
            },
        ) { request ->
            tool {
                val id = request.string("surfaceId")
                val data = request.objectOrEmpty("data").entries.map { it.key to it.value }
                bridge.update(id, request.objectsOrEmpty("components"), data, request.appends())
                answer(buildJsonObject { put("updated", id) })
            }
        }
        server.addTool(
            name = CLOSE,
            description = "Puts a surface's closing $SHARED. A closed surface keeps its content and records.",
            inputSchema = schema(required = listOf("surfaceId")) { putJsonObject("surfaceId") { put("type", "string") } },
        ) { request ->
            tool {
                val id = request.string("surfaceId")
                bridge.close(id)
                answer(buildJsonObject { put("closed", id) })
            }
        }
        server.addTool(
            name = CLEAR,
            description = "Puts a cleared board $SHARED: closes every open surface but the host's, and deletes none.",
            inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
        ) { _ -> tool { answer(buildJsonObject { put("closed", JsonArray(bridge.clear().map(::JsonPrimitive))) }) } }
        server.addTool(
            name = DONE,
            description = "Puts a handled record $SHARED: completes the claim bee_wait_action returned, under " +
                "its token. Refused if the token is stale.",
            inputSchema = schema(required = listOf("recordId", "token")) {
                putJsonObject("recordId") { put("type", "string") }
                putJsonObject("token") { put("type", "string") }
                putJsonObject("result") { put("type", "string") }
            },
        ) { request ->
            tool {
                val record = BoardActionSource.parseRecordId(request.string("recordId"))
                val done = bridge.done(BoardActionSource.target(record), BoardActionSource.parseToken(request.string("token")))
                if (!done) throw BridgeRefusal("Record ${request.string("recordId")} was not completed: the token is stale or the claim isn't yours.")
                answer(buildJsonObject { put("done", request.string("recordId")) })
            }
        }
        server.addTool(
            name = CLAIM,
            description = "Puts a claim $SHARED: takes a target (record:<author>:<seq> or card:<surfaceId>/<cardId>) " +
                "for this session, through the host. Returns won with a token, lost with the holder, or unavailable.",
            inputSchema = schema(required = listOf("target")) { putJsonObject("target") { put("type", "string") } },
        ) { request -> tool { answer(outcome(bridge.claim(ClaimTarget(request.string("target"))))) } }
        server.addTool(
            name = RELEASE,
            description = "Puts a release $SHARED: frees a target this session holds, under its token, or an " +
                "orphaned target without one.",
            inputSchema = schema(required = listOf("target")) {
                putJsonObject("target") { put("type", "string") }
                putJsonObject("token") { put("type", "string") }
            },
        ) { request ->
            tool {
                val token = request.optional("token")?.let(BoardActionSource::parseToken)
                answer(buildJsonObject { put("released", bridge.release(ClaimTarget(request.string("target")), token)) })
            }
        }
        server.addTool(
            name = PRESENCE,
            description = "Puts this session's \"working on…\" line $SHARED, beside its name. Omit status to clear it.",
            inputSchema = schema(required = emptyList()) { putJsonObject("status") { put("type", "string") } },
        ) { request ->
            tool {
                bridge.status(request.optional("status"))
                answer(buildJsonObject { put("as", bridge.self.toString()) })
            }
        }
    }

    private fun board(state: BoardState, you: PeerIdentity, roster: Collection<PresenceEntry>, claimsOnline: Boolean): JsonObject = buildJsonObject {
        put("you", you.toString())
        put(
            "open",
            buildJsonArray {
                state.openSurfaces().forEach { id ->
                    val meta = state.meta(id)!!
                    val view = state.surfaceDoc(id)!!.toSurfaceView(id)
                    add(
                        buildJsonObject {
                            put("surfaceId", id)
                            put("title", meta.title)
                            put("openedBy", meta.openedBy.toString())
                            put("components", JsonArray(view.components.values.toList()))
                            put("data", view.data)
                        },
                    )
                }
            },
        )
        put("closed", JsonArray(state.closedSurfaces().map(::JsonPrimitive)))
        put(
            "claimViews",
            JsonObject(state.claims.entries.mapValues { (_, v) -> buildJsonObject { put("status", v.status); put("holder", v.holder); put("token", v.token) } }),
        )
        put("departed", JsonArray(state.departures().map { JsonPrimitive("${it.identity} (${it.reason})") }))
        // A departed identity never comes back, though its last presence entry lingers until it ages out.
        val gone = state.departures().map { it.identity }.toSet()
        put(
            "attached",
            JsonArray(
                roster.filterNot { it.identity in gone }.sortedBy { it.identity.toString() }.map { entry ->
                    buildJsonObject {
                        put("identity", entry.identity.toString())
                        put("status", entry.status)
                        put("waiting", entry.waiting)
                        put("lastActedAt", entry.lastActedAt.toString())
                    }
                },
            ),
        )
        put("claims", if (claimsOnline) "online" else "offline")
    }

    private fun outcome(outcome: ClaimOutcome): JsonObject = buildJsonObject {
        when (outcome) {
            is ClaimOutcome.Won -> { put("outcome", "won"); put("token", BoardActionSource.tokenText(outcome.token)) }
            is ClaimOutcome.Lost -> { put("outcome", "lost"); put("holder", outcome.holder.toString()) }
            ClaimOutcome.Unavailable -> put("outcome", "unavailable")
            ClaimOutcome.Retired -> put("outcome", "retired")
        }
    }

    private fun schema(required: List<String>, properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ToolSchema(properties = buildJsonObject(properties), required = required)

    private fun answer(json: JsonObject) = CallToolResult(content = listOf(TextContent(json.toString())), structuredContent = json)

    /** Runs a tool, turning a refusal or a malformed argument into a tool error that names it. */
    private inline fun tool(block: () -> CallToolResult): CallToolResult = try {
        block()
    } catch (refused: BridgeRefusal) {
        CallToolResult(content = listOf(TextContent(refused.message.orEmpty())), isError = true)
    } catch (bad: IllegalArgumentException) {
        CallToolResult(content = listOf(TextContent(bad.message.orEmpty())), isError = true)
    }

    private fun CallToolRequest.arg(key: String): JsonElement? = arguments?.get(key)?.takeUnless { it is JsonNull }

    private fun CallToolRequest.optional(key: String): String? = when (val v = arg(key)) {
        null -> null
        is JsonPrimitive -> v.content.takeIf { v.isString } ?: throw IllegalArgumentException("$key must be a string; got $v.")
        else -> throw IllegalArgumentException("$key must be a string; got $v.")
    }

    private fun CallToolRequest.string(key: String): String = optional(key) ?: throw IllegalArgumentException("$key is required.")

    private fun CallToolRequest.objects(key: String): List<JsonObject> =
        (arg(key) as? JsonArray ?: throw IllegalArgumentException("$key must be an array of objects."))
            .map { it as? JsonObject ?: throw IllegalArgumentException("$key must be an array of objects; got $it.") }

    private fun CallToolRequest.objectsOrEmpty(key: String): List<JsonObject> = if (arg(key) == null) emptyList() else objects(key)

    /** The `append` argument both surface writes take: a pointer to the entries appended there. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.appendProperty() = putJsonObject(APPEND) {
        put("type", "object")
        put("description", "Pointer to entries appended, e.g. {\"/options\": [\"Tacos\"]}.")
    }

    /** [APPEND]'s entries, one pair per entry, in order: `{"/options": ["a", "b"]}` is two. */
    private fun CallToolRequest.appends(): List<Pair<String, JsonElement>> =
        objectOrEmpty(APPEND).entries.flatMap { (pointer, entries) ->
            val array = entries as? JsonArray
                ?: throw IllegalArgumentException("$APPEND maps each pointer to an array of entries; $pointer has $entries.")
            array.map { pointer to it }
        }

    private const val APPEND = "append"

    private fun CallToolRequest.objectOrEmpty(key: String): JsonObject = when (val v = arg(key)) {
        null -> JsonObject(emptyMap())
        is JsonObject -> v
        else -> throw IllegalArgumentException("$key must be an object; got $v.")
    }
}
