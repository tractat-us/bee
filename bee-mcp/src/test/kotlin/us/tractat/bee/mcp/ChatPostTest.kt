package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NotionActions
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An agent talks in a Chat (bee#214) through the tools it already has: `append` on
 * `bee_surface_open` or `bee_surface_update` adds a message, and the bridge refuses a malformed
 * one, leaving the Chat as it was.
 */
class ChatPostTest {
    @Test
    fun anAgentPostsAMessageThatEveryPeerSees() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()

        val opened = ada.call(BeeTools.OPEN, chatArgs("talk", append = mapOf("/messages" to listOf(message("m1", "Ask me here.")))))
        val posted = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to append("/messages" to listOf(message("m2", "<b>Still</b> here.")))))
        val seen = browser.state.first { texts(it.surfaceDoc("talk")?.toSurfaceView("talk")?.data).size == 2 }
        assertAll(
            { assertEquals(null, opened.isError, opened.text()) },
            { assertEquals(null, posted.isError, posted.text()) },
            { assertEquals(listOf("Ask me here.", "<b>Still</b> here."), texts(seen.surfaceDoc("talk")!!.toSurfaceView("talk").data)) },
        )
        rig.close()
    }

    @Test
    fun aMalformedMessageIsRefusedAndTheChatIsUnchanged() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, chatArgs("talk", append = mapOf("/messages" to listOf(message("m1", "hi")))))

        val overlong = ada.call(
            BeeTools.UPDATE,
            mapOf("surfaceId" to "talk", "append" to append("/messages" to listOf(message("m2", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH + 1))))),
        )
        val bare = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to Json.parseToJsonElement("""{"/messages": ["just text"]}""")))
        val asData = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "data" to Json.parseToJsonElement("""{"/messages": []}""")))
        val control = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to append("/messages" to listOf(message("m3", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH))))))
        val shown = texts(ada.client.state.value.surfaceDoc("talk")!!.toSurfaceView("talk").data)
        assertAll(
            { assertTrue(overlong.isError == true && "past the ${NotionActions.MAX_MESSAGE_LENGTH}" in overlong.text(), overlong.text()) },
            { assertTrue(bare.isError == true && "{id, author, text, at}" in bare.text(), bare.text()) },
            { assertTrue(asData.isError == true, "messages can't be rewritten as data: ${asData.text()}") },
            { assertEquals(null, control.isError, "control: a message at the cap posts: ${control.text()}") },
            { assertEquals(listOf("hi", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH)), shown, "only the well-formed messages reached the board") },
        )
        rig.close()
    }

    private fun chatArgs(id: String, append: Map<String, List<JsonObject>>): Map<String, Any?> = mapOf(
        "surfaceId" to id,
        "title" to "Talk",
        "components" to JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "root")
                    put("component", "Chat")
                    put("messages", buildJsonObject { put("path", "/messages") })
                },
            ),
        ),
        "append" to append(*append.entries.map { it.key to it.value }.toTypedArray()),
    )

    private fun append(vararg entries: Pair<String, List<JsonObject>>) = JsonObject(entries.associate { (pointer, messages) -> pointer to JsonArray(messages) })

    private fun message(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("author", "agent:ada#00000000")
        put("text", text)
        put("at", "2026-11-09T12:00:00Z")
    }

    private fun texts(data: JsonObject?): List<String> =
        (data?.get("messages") as? JsonArray).orEmpty().map { it.jsonObject.getValue("text").jsonPrimitive.content }
}
