package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NotionActions
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * An agent talks in a Chat (bee#214) through the `append` it already has. It sends only text: the
 * bridge stamps each message with the session's own identity, a fresh id and the time, and records
 * a `send` as a page's Send does, so another session waiting on the Chat hears it. A message that
 * names someone else, or brings its own id or time, is refused, and the Chat is unchanged.
 */
class ChatPostTest {
    @Test
    fun anAgentsMessageIsStampedAsItsSessionAndRecorded() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val browser = rig.browser()

        val opened = ada.call(BeeTools.OPEN, chatArgs("talk", append = posts("Ask me here.")))
        val posted = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to posts("<b>Still</b> here.")))
        val seen = browser.state.first { it.surfaceDoc("talk") != null && messages(it, "talk").size == 2 && it.records("talk").size == 2 }
        val shown = messages(seen, "talk")
        val records = seen.records("talk").map { it.second }
        assertAll(
            { assertEquals(null, opened.isError, opened.text()) },
            { assertEquals(null, posted.isError, posted.text()) },
            { assertEquals(listOf("Ask me here.", "<b>Still</b> here."), shown.map { it.string("text") }) },
            { assertEquals(List(2) { ada.bridge.self.toString() }, shown.map { it.string("author") }, "stamped as the session") },
            { assertEquals(2, shown.map { it.string("id") }.toSet().size, "each id is fresh") },
            { assertTrue(shown.all { runCatching { Instant.parse(it.string("at")) }.isSuccess }, "at is a time: $shown") },
            { assertEquals(List(2) { NotionActions.SEND }, records.map { it.name }) },
            { assertEquals(List(2) { ada.bridge.self }, records.map { it.actor }) },
            { assertEquals(shown.map { it.string("id") }, records.map { it.context.getValue("id").jsonPrimitive.content }, "each record names its message") },
        )
        rig.close()
    }

    @Test
    fun aMessageThatSpoofsOrStampsItselfIsRefusedAndTheChatIsUnchanged() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, chatArgs("talk", append = posts("hi")))
        val self = ada.bridge.self.toString()

        fun post(vararg fields: Pair<String, String>): Map<String, Any?> =
            mapOf("surfaceId" to "talk", "append" to JsonObject(mapOf("/messages" to JsonArray(listOf(fields(*fields))))))
        val spoofed = ada.call(BeeTools.UPDATE, post("text" to "I agree.", "author" to "human:alex"))
        val ownId = ada.call(BeeTools.UPDATE, post("text" to "mine", "id" to "m1"))
        val overlong = ada.call(BeeTools.UPDATE, post("text" to "x".repeat(NotionActions.MAX_MESSAGE_LENGTH + 1)))
        val bare = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to Json.parseToJsonElement("""{"/messages": ["just text"]}""")))
        val asData = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "data" to Json.parseToJsonElement("""{"/messages": []}""")))
        val asSelf = ada.call(BeeTools.UPDATE, post("text" to "me", "author" to self))
        val atCap = ada.call(BeeTools.UPDATE, post("text" to "x".repeat(NotionActions.MAX_MESSAGE_LENGTH)))
        val state = ada.client.state.value
        assertAll(
            { assertTrue(spoofed.isError == true && "can't name author \"human:alex\"" in spoofed.text(), spoofed.text()) },
            { assertTrue(ownId.isError == true && "carries only text" in ownId.text(), ownId.text()) },
            { assertTrue(overlong.isError == true && "past the ${NotionActions.MAX_MESSAGE_LENGTH}" in overlong.text(), overlong.text()) },
            { assertTrue(bare.isError == true && "{\"text\": \"…\"}" in bare.text(), bare.text()) },
            { assertTrue(asData.isError == true, "messages can't be rewritten as data: ${asData.text()}") },
            { assertEquals(null, asSelf.isError, "control: naming its own identity posts: ${asSelf.text()}") },
            { assertEquals(null, atCap.isError, "control: a message at the cap posts: ${atCap.text()}") },
            { assertEquals(listOf("hi", "me", "x".repeat(NotionActions.MAX_MESSAGE_LENGTH)), messages(state, "talk").map { it.string("text") }) },
            { assertEquals(listOf(self), messages(state, "talk").map { it.string("author") }.distinct(), "nobody else's name reached the board") },
            { assertEquals(3, state.records("talk").size, "a refused message records nothing") },
        )
        rig.close()
    }

    /**
     * ada's own wait never hands her message back to her, so she doesn't answer herself; bo,
     * waiting on her Chat, hears it. ada waits first, while nobody else could claim the record, so
     * only the filter can keep it from her.
     */
    @Test
    fun anotherSessionHearsAnAgentsMessageAndItsAuthorDoesNot() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val bo = rig.session("bo")
        ada.call(BeeTools.OPEN, chatArgs("talk", append = JsonObject(emptyMap())))
        ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "talk", "append" to posts("bo, take the tests?")))
        bo.client.state.first { it.records("talk").isNotEmpty() }

        val own = ada.wait(mapOf("timeoutSeconds" to 1))
        val heard = bo.wait(mapOf("surfaceId" to "talk", "timeoutSeconds" to 30))
        val record = heard.getValue("record").jsonObject
        assertAll(
            { assertEquals(true, own.getValue("timedOut").jsonPrimitive.boolean, "ada's board-wide wait doesn't hear her own message: $own") },
            { assertEquals(NotionActions.SEND, record.getValue("name").jsonPrimitive.content) },
            { assertEquals(ada.bridge.self.toString(), record.getValue("actor").jsonPrimitive.content) },
            { assertEquals("bo, take the tests?", record.getValue("context").jsonObject.getValue("text").jsonPrimitive.content) },
        )
        rig.close()
    }

    /** A one-surface wait or list leaves out ada's own post too: she never receives what she wrote. */
    @Test
    fun aOneSurfaceWaitDoesNotReturnTheSessionsOwnPost() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        ada.call(BeeTools.OPEN, chatArgs("talk", append = posts("Note to self.")))
        ada.client.state.first { it.records("talk").isNotEmpty() }

        val own = ada.wait(mapOf("surfaceId" to "talk", "timeoutSeconds" to 1))
        val listed = ada.call(WaitTool.LIST, mapOf("surfaceId" to "talk")).json().getValue("records") as JsonArray
        assertAll(
            { assertEquals(1, ada.client.state.value.records("talk").size, "rig: the post was recorded") },
            { assertEquals(true, own.getValue("timedOut").jsonPrimitive.boolean, "ada's one-surface wait doesn't return her own post: $own") },
            { assertEquals(0, listed.size, "nor does her list: $listed") },
        )
        rig.close()
    }

    /**
     * Posting as someone else takes no second call either (#217's re-review). An entry appended raw
     * to a path no Chat binds can't be shown afterwards by binding a Chat there, new or rebound; and
     * once a Chat binds the path, an append there is a post, stamped, so a foreign author is refused.
     * A raw append to a path no Chat binds is still fine on its own: it's a plain append-only array.
     */
    @Test
    fun noTwoCallsPostAsAnotherAuthorInEitherOrder() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val forged = JsonObject(
            mapOf("/fake" to JsonArray(listOf(fields("id" to "x1", "author" to "human:alex", "text" to "I approve.", "at" to "2026-11-09T12:00:00Z")))),
        )
        // Once a Chat binds /fake, an append there is a post: text only, so a foreign author is what's left to try.
        val spoofed = JsonObject(mapOf("/fake" to JsonArray(listOf(fields("text" to "I approve.", "author" to "human:alex")))))
        val rebound = JsonArray(listOf(chat("chat", "/fake")))
        val withNewChat = JsonArray(listOf(column("root", "chat", "late"), chat("late", "/fake")))
        fun update(id: String, key: String, value: JsonElement) = mapOf("surfaceId" to id, key to value)
        listOf("raw-rebind", "raw-new", "rebind-raw", "new-raw").forEach { id ->
            ada.call(BeeTools.OPEN, mapOf("surfaceId" to id, "title" to "Talk", "components" to JsonArray(listOf(column("root", "chat"), chat("chat", "/messages")))))
        }
        val rawFirst = ada.call(BeeTools.UPDATE, update("raw-rebind", "append", forged))
        val thenRebind = ada.call(BeeTools.UPDATE, update("raw-rebind", "components", rebound))
        ada.call(BeeTools.UPDATE, update("raw-new", "append", forged))
        val thenNewChat = ada.call(BeeTools.UPDATE, update("raw-new", "components", withNewChat))
        val rebindFirst = ada.call(BeeTools.UPDATE, update("rebind-raw", "components", rebound))
        val thenForgeRebound = ada.call(BeeTools.UPDATE, update("rebind-raw", "append", spoofed))
        val newChatFirst = ada.call(BeeTools.UPDATE, update("new-raw", "components", withNewChat))
        val thenForgeNew = ada.call(BeeTools.UPDATE, update("new-raw", "append", spoofed))
        val state = ada.client.state.value
        assertAll(
            { assertEquals(null, rawFirst.isError, "control: a raw append no Chat shows is fine: ${rawFirst.text()}") },
            { assertTrue(thenRebind.isError == true && "didn't post" in thenRebind.text(), thenRebind.text()) },
            { assertTrue(thenNewChat.isError == true && "didn't post" in thenNewChat.text(), thenNewChat.text()) },
            { assertEquals(null, rebindFirst.isError, "control: rebinding to an empty path is fine: ${rebindFirst.text()}") },
            { assertTrue(thenForgeRebound.isError == true && "can't name author \"human:alex\"" in thenForgeRebound.text(), thenForgeRebound.text()) },
            { assertEquals(null, newChatFirst.isError, "control: a new Chat over an empty path is fine: ${newChatFirst.text()}") },
            { assertTrue(thenForgeNew.isError == true && "can't name author \"human:alex\"" in thenForgeNew.text(), thenForgeNew.text()) },
            {
                assertEquals(
                    emptyList(),
                    listOf("raw-rebind", "raw-new", "rebind-raw", "new-raw").flatMap { shownAuthors(state, it) },
                    "no Chat shows a message ada didn't post",
                )
            },
        )
        rig.close()
    }

    private fun column(id: String, vararg children: String) = buildJsonObject {
        put("id", id)
        put("component", "Column")
        put("children", JsonArray(children.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    private fun chat(id: String, path: String) = buildJsonObject {
        put("id", id)
        put("component", "Chat")
        put("messages", buildJsonObject { put("path", path) })
    }

    /** The authors of every message any Chat on surface [id] shows, read straight from its components and data. */
    private fun shownAuthors(state: BoardState, id: String): List<String> {
        val view = state.surfaceDoc(id)!!.toSurfaceView(id)
        return view.components.values.filter { it["component"]?.jsonPrimitive?.content == "Chat" }.flatMap { chat ->
            val key = chat.getValue("messages").jsonObject.getValue("path").jsonPrimitive.content.removePrefix("/")
            (view.data[key] as? JsonArray).orEmpty().map { it.jsonObject.getValue("author").jsonPrimitive.content }
        }
    }

    private suspend fun Session.wait(arguments: Map<String, Any?>): JsonObject {
        val result = call(WaitTool.WAIT, arguments)
        check(result.isError != true) { "the wait was refused: ${result.text()}" }
        return result.json()
    }

    private fun chatArgs(id: String, append: JsonObject): Map<String, Any?> = mapOf(
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
        "append" to append,
    )

    /** An `append` posting [texts] to `/messages`, each only its text, as an agent posts. */
    private fun posts(vararg texts: String): JsonObject =
        JsonObject(mapOf("/messages" to JsonArray(texts.map { text -> fields("text" to text) })))

    private fun fields(vararg fields: Pair<String, String>) = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }

    private fun messages(state: BoardState, id: String): List<JsonObject> =
        (state.surfaceDoc(id)!!.toSurfaceView(id).data["messages"] as? JsonArray).orEmpty().map { it.jsonObject }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
