package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NotionActionsTest {
    @Test
    fun anAnswerWritesTheActorsOwnAnswer() {
        assertEquals(
            NotionEdit("/answers/human:alex", JsonPrimitive("lunch")),
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "lunch"), "human:alex"),
        )
    }

    @Test
    fun anAnswerEscapesTheActorAsOneKey() {
        assertEquals(
            "/answers/agent:a~1b~0c#0badf00d",
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "lunch"), "agent:a/b~c#0badf00d")?.path,
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
            { assertEquals(JsonPrimitive("r1"), NotionActions.edit(view, action("poll", NotionActions.ANSWER, "option" to "r1"), "human:alex")?.value) },
            { assertEquals(JsonPrimitive("Wait"), NotionActions.edit(view, action("poll", NotionActions.ANSWER, "option" to "Wait"), "human:alex")?.value) },
            { assertEquals(listOf("r1", "Wait"), NotionActions.optionIds(options)) },
        )
    }

    @Test
    fun anAnswerNamingNoOptionIsRefused() {
        val refused = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(poll(), action("poll", NotionActions.ANSWER, "option" to "brunch"), "human:alex")
        }
        assertEquals("Poll \"poll\" has no option \"brunch\".", refused.message)
    }

    @Test
    fun aDropWritesTheCardsColumn() {
        assertEquals(
            NotionEdit("/work/cards/items/c1/column", JsonPrimitive("doing")),
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c1", "column" to "doing"), "human:alex"),
        )
    }

    @Test
    fun aDropOfAnUnknownCardOrOntoAnUnknownColumnIsRefused() {
        val noCard = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c9", "column" to "doing"), "human:alex")
        }
        val noColumn = assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(board(), action("board", NotionActions.DROP, "card" to "c1", "column" to "done"), "human:alex")
        }
        assertAll(
            { assertEquals("Board \"board\" has no card \"c9\".", noCard.message) },
            { assertEquals("Board \"board\" has no column \"done\".", noColumn.message) },
        )
    }

    @Test
    fun anyOtherActionOnlyRecords() {
        val view = poll().let { it.copy(components = it.components + ("go" to component("go", "Button"))) }
        assertAll(
            { assertNull(NotionActions.edit(view, action("go", "press"), "human:alex")) },
            { assertNull(NotionActions.edit(view, action("poll", "press"), "human:alex")) },
        )
    }

    private fun poll(options: JsonArray = JsonArray(listOf(JsonPrimitive("lunch"), JsonPrimitive("dinner")))) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "poll" to component("poll", SurfaceValidator.POLL) {
                putJsonObject("question") { put("path", "/question") }
                putJsonObject("options") { put("path", "/options") }
                putJsonObject("answers") { put("path", "/answers") }
            },
        ),
        data = buildJsonObject {
            put("question", JsonArray(listOf(JsonPrimitive("Where?"))))
            put("options", options)
            putJsonObject("answers") { put("human:sam", "dinner") }
        },
        conflicts = emptyMap(),
    )

    private fun board() = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "board" to component("board", SurfaceValidator.BOARD) {
                putJsonObject("columns") { put("path", "/work/columns") }
                putJsonObject("cards") { put("path", "/work/cards") }
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
}
