package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class NotionActionsTest {
    @Test
    fun anAnswerWritesTheActorsOwnAnswer() {
        assertEquals(
            NotionEdit.Write("/answers/human:alex", JsonPrimitive("lunch")),
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "lunch"), "human:alex", AT),
        )
    }

    @Test
    fun anAnswerEscapesTheActorAsOneKey() {
        assertEquals(
            "/answers/agent:a~1b~0c#0badf00d",
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "lunch"), "agent:a/b~c#0badf00d", AT)?.path,
        )
    }

    @Test
    fun anAnswerNamesAnOptionById() {
        val options = JsonArray(
            listOf(
                buildJsonObject { put("id", "r1"); put("label", "Release") },
                JsonPrimitive("Wait"),
            ),
        )
        val view = poll(options = options)
        assertAll(
            { assertEquals(JsonPrimitive("r1"), NotionActions.edit(view, action("poll", NotionActions.ANSWER, "option" to "r1"), "human:alex", AT)?.value) },
            { assertEquals(JsonPrimitive("Wait"), NotionActions.edit(view, action("poll", NotionActions.ANSWER, "option" to "Wait"), "human:alex", AT)?.value) },
            { assertEquals(listOf("r1", "Wait"), NotionActions.optionIds(options)) },
        )
    }

    @Test
    fun anAnswerNamingNoOptionIsRefused() {
        val refused = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "brunch"), "human:alex", AT)
        }
        assertEquals("Poll \"poll\" has no option \"brunch\".", refused.message)
    }

    @Test
    fun aDropWritesTheCardsColumn() {
        assertEquals(
            NotionEdit.Write("/work/cards/items/c1/column", JsonPrimitive("doing")),
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c1", "column" to "doing"), "human:alex", AT),
        )
    }

    @Test
    fun aDropOfAnUnknownCardOrOntoAnUnknownColumnIsRefused() {
        val noCard = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c9", "column" to "doing"), "human:alex", AT)
        }
        val noColumn = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c1", "column" to "done"), "human:alex", AT)
        }
        assertAll(
            { assertEquals("Board \"board\" has no card \"c9\".", noCard.message) },
            { assertEquals("Board \"board\" has no column \"done\".", noColumn.message) },
        )
    }

    /** A Chat send appends the message, authored by the actor and stamped with the record's time (bee#214). */
    @Test
    fun aSendAppendsTheMessageAsItsActor() {
        assertEquals(
            NotionEdit.Append(
                "/talk/messages",
                buildJsonObject {
                    put("id", "m1")
                    put("author", "human:alex")
                    put("text", "Ship it <b>now</b>")
                    put("at", "2026-11-09T12:00:00Z")
                },
            ),
            NotionActions.edit(chat(), action("chat", NotionActions.SEND, "id" to "m1", "text" to "Ship it <b>now</b>"), "human:alex", AT),
        )
    }

    /** A blank message, one past the cap, or one with no id is refused, so nothing is appended. */
    @Test
    fun aBlankOverlongOrUnnamedMessageIsRefused() {
        fun refusal(vararg context: Pair<String, String>) = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(chat(), action("chat", NotionActions.SEND, *context), "human:alex", AT)
        }.message
        val longest = "x".repeat(NotionActions.MAX_MESSAGE_LENGTH)
        assertAll(
            { assertEquals("A message on Chat \"chat\" is empty.", refusal("id" to "m1", "text" to " \n ")) },
            {
                assertEquals(
                    "A message on Chat \"chat\" is ${longest.length + 1} characters, past the ${NotionActions.MAX_MESSAGE_LENGTH} a message holds.",
                    refusal("id" to "m1", "text" to longest + "x"),
                )
            },
            { assertEquals("A message on Chat \"chat\" has no id.", refusal("id" to "", "text" to "hi")) },
            { assertEquals("A send on \"chat\" has no id.", refusal("text" to "hi")) },
            { assertEquals(longest, (NotionActions.edit(chat(), action("chat", NotionActions.SEND, "id" to "m1", "text" to longest), "human:alex", AT) as NotionEdit.Append).value.jsonObject["text"]?.jsonPrimitive?.content) },
        )
    }

    /** A page's send reusing an id the Chat already holds is refused, never appended as a second "m1". */
    @Test
    fun aSendReusingAnIdIsRefused() {
        val refused = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(chat("m1"), action("chat", NotionActions.SEND, "id" to "m1", "text" to "again"), "human:alex", AT)
        }
        assertEquals("A message on Chat \"chat\" reuses id \"m1\", which an earlier message holds.", refused.message)
    }

    /** An append to a path a Chat binds is a post; any other path isn't. */
    @Test
    fun chatAtNamesTheChatThatBindsAPath() {
        assertAll(
            { assertEquals("chat", NotionActions.chatAt(chat(), "/talk/messages")) },
            { assertNull(NotionActions.chatAt(chat(), "/talk/other")) },
            { assertNull(NotionActions.chatAt(poll(), "/options")) },
        )
    }

    /** A session posts only text: its message is stamped with the session's identity, a fresh id and the time (bee#214). */
    @Test
    fun aPostIsStampedAsItsSession() {
        val stamped = buildJsonObject {
            put("id", "p1")
            put("author", ADA)
            put("text", "Ship it")
            put("at", "2026-11-09T12:00:00Z")
        }
        assertAll(
            { assertEquals(stamped, NotionActions.post("chat", buildJsonObject { put("text", "Ship it") }, ADA, "p1", AT)) },
            {
                assertEquals(
                    stamped,
                    NotionActions.post("chat", buildJsonObject { put("text", "Ship it"); put("author", ADA) }, ADA, "p1", AT),
                    "naming its own identity is fine",
                )
            },
        )
    }

    /** A post naming someone else, carrying its own id or time, or with no text, is refused, never quietly rewritten. */
    @Test
    fun aPostThatSpoofsOrStampsItselfIsRefused() {
        fun refusal(entry: kotlinx.serialization.json.JsonElement) =
            assertFailsWith<IllegalArgumentException> { NotionActions.post("chat", entry, ADA, "p1", AT) }.message
        assertAll(
            {
                assertEquals(
                    "A message on Chat \"chat\" can't name author \"human:alex\": it posts as $ADA.",
                    refusal(buildJsonObject { put("text", "hi"); put("author", "human:alex") }),
                )
            },
            {
                assertEquals(
                    "A message on Chat \"chat\" carries only text: its id, author and at are stamped for it.",
                    refusal(buildJsonObject { put("text", "hi"); put("id", "mine") }),
                )
            },
            { assertEquals("A message on Chat \"chat\" is {\"text\": \"…\"}; got \"hi\".", refusal(JsonPrimitive("hi"))) },
            { assertEquals("A message on Chat \"chat\" is empty.", refusal(buildJsonObject { put("text", " ") })) },
        )
    }

    @Test
    fun anyOtherActionOnlyRecords() {
        val view = poll().let { it.copy(components = it.components + ("go" to component("go", "Button"))) }
        assertAll(
            { assertNull(NotionActions.edit(view, action("go", "press"), "human:alex", AT)) },
            { assertNull(NotionActions.edit(view, action("poll", "press"), "human:alex", AT)) },
        )
    }

    private fun poll(options: JsonArray = JsonArray(listOf(JsonPrimitive("lunch"), JsonPrimitive("dinner")))) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "poll" to component("poll", SurfaceValidator.POLL) {
                putJsonObject("question") { put("@path", "/question") }
                putJsonObject("options") { put("@path", "/options") }
                putJsonObject("answers") { put("@path", "/answers") }
            },
        ),
        data = buildJsonObject {
            put("question", JsonArray(listOf(JsonPrimitive("Where?"))))
            put("options", options)
            putJsonObject("answers") { put("human:sam", "dinner") }
        },
        conflicts = emptyMap(),
        appended = emptySet(),
    )

    private fun chat(vararg ids: String) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "chat" to component("chat", SurfaceValidator.CHAT) { putJsonObject("messages") { put("@path", "/talk/messages") } },
        ),
        data = buildJsonObject {
            putJsonObject("talk") {
                put("messages", JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("author", "human:sam"); put("text", "hi"); put("at", "2026-11-09T11:00:00Z") } }))
            }
        },
        conflicts = emptyMap(),
        appended = emptySet(),
    )

    private fun board() = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "board" to component("board", SurfaceValidator.BOARD) {
                putJsonObject("columns") { put("@path", "/work/columns") }
                putJsonObject("cards") { put("@path", "/work/cards") }
            },
        ),
        data = buildJsonObject {
            putJsonObject("work") {
                putJsonObject("columns") {
                    putJsonObject("items") {
                        putJsonObject("todo") { put("title", "To do") }
                        putJsonObject("doing") { put("title", "Doing") }
                    }
                    put("order", JsonArray(listOf(JsonPrimitive("todo"), JsonPrimitive("doing"))))
                }
                putJsonObject("cards") {
                    putJsonObject("items") { putJsonObject("c1") { put("label", "Spec"); put("column", "todo") } }
                    put("order", JsonArray(listOf(JsonPrimitive("c1"))))
                }
            }
        },
        conflicts = emptyMap(),
        appended = emptySet(),
    )

    private fun action(component: String, name: String, vararg context: Pair<String, String>) = A2uiAction(
        name = name,
        surfaceId = "s",
        sourceComponentId = component,
        timestamp = "2026-11-08T12:00:00Z",
        context = JsonObject(context.associate { (k, v) -> k to JsonPrimitive(v) }),
    )

    private fun component(id: String, type: String, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("id", id)
            put("component", type)
            more()
        }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-09T12:00:00Z")
        const val ADA = "agent:ada#0badf00d"
    }
}
