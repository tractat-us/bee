package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.A2uiBindings
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * An agent whose skill predates A2UI v1.0 still writes v0.9.1 bindings, `{"path": "/…"}` (bee#230).
 * The bridge validates the surface as every reader sees it, upgraded to `{"@path"}`
 * ([A2uiBindings.upgrade]), so its Poll and Chat open and its Chat takes posts. What it wrote is
 * stored as written; only the readout is in v1.0's spelling.
 */
class V091BindingsTest {
    @Test
    fun aPollAndChatBoundInV091OpenTakeAPostAndAreStoredAsWritten() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val opened = ada.call(
            BeeTools.OPEN,
            mapOf(
                "surfaceId" to "older",
                "title" to "Older skill",
                "components" to JsonArray(listOf(column("root", "poll", "chat"), poll(), chat())),
                "data" to Json.parseToJsonElement("""{"answers": {}}"""),
                "append" to Json.parseToJsonElement("""{"/question": ["Which cache?"], "/options": ["Redis", "Memcached"]}"""),
            ),
        )
        val posted = ada.call(
            BeeTools.UPDATE,
            mapOf("surfaceId" to "older", "append" to Json.parseToJsonElement("""{"/messages": [{"text": "Still here."}]}""")),
        )
        val state = ada.client.state.value
        // Read leniently, so a refused open fails on its own message below rather than here.
        val readout = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("open").jsonArray
            .flatMap { it.jsonObject.getValue("components").jsonArray }
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        assertAll(
            { assertEquals(null, opened.isError, "the v0.9.1 Poll and Chat open: ${opened.text()}") },
            { assertEquals(null, posted.isError, "the v0.9.1 Chat takes a post: ${posted.text()}") },
            { assertEquals(listOf("Still here."), messages(state).map { it.getValue("text").jsonPrimitive.content }) },
            { assertEquals(listOf(ada.bridge.self.toString()), messages(state).map { it.getValue("author").jsonPrimitive.content }, "stamped as the session") },
            { assertEquals(legacy("/messages"), stored(state, "chat")?.get("messages"), "the Chat is stored as written") },
            { assertEquals(legacy("/options"), stored(state, "poll")?.get("options"), "the Poll is stored as written") },
            { assertEquals(A2uiBindings.binding("/messages"), readout["chat"]?.get("messages"), "bee_board reads the Chat in v1.0") },
            {
                assertEquals(
                    POLL_BINDINGS.map(A2uiBindings::binding),
                    POLL_BINDINGS.map { readout["poll"]?.get(it.removePrefix("/")) },
                    "bee_board reads the Poll in v1.0",
                )
            },
        )
        rig.close()
    }

    // The helpers return nothing, rather than throw, for a surface that never opened: kuilt's
    // assertAll rethrows anything but an AssertionError, which would hide the refusal's message.

    /** Component [id] of surface `older` as the board's CRDT stores it, not as any view shows it. */
    private fun stored(state: BoardState, id: String): JsonObject? {
        val components = state.surfaceDoc("older")?.get(SurfaceFormat.COMPONENTS) as? JsonNode.Object ?: return null
        return (components.map[id] as? JsonNode.Leaf)?.let { SurfaceFormat.winner(it).jsonObject }
    }

    private fun messages(state: BoardState): List<JsonObject> =
        (state.surfaceDoc("older")?.toSurfaceView("older")?.data?.get("messages") as? JsonArray).orEmpty().map { it.jsonObject }

    private fun poll() = buildJsonObject {
        put("id", "poll")
        put("component", "Poll")
        POLL_BINDINGS.forEach { path -> put(path.removePrefix("/"), legacy(path)) }
    }

    private fun chat() = buildJsonObject {
        put("id", "chat")
        put("component", "Chat")
        put("messages", legacy("/messages"))
    }

    /** A v0.9.1 binding to [path]. */
    private fun legacy(path: String) = JsonObject(mapOf("path" to JsonPrimitive(path)))

    private companion object {
        val POLL_BINDINGS = listOf("/question", "/options", "/answers")
    }
}
