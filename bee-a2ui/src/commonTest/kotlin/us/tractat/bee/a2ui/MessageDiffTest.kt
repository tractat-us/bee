package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageDiffTest {
    private val root = buildJsonObject {
        put("id", "root")
        put("component", "Column")
        put("children", JsonArray(listOf(JsonPrimitive("title"))))
    }
    private val title = text("title", "Lunch?")
    private val view = SurfaceView(
        id = "lunch",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf("root" to root, "title" to title),
        data = buildJsonObject {
            put("poll", buildJsonObject { put("question", "Where?"); put("votes", 2) })
            put("order", ids("a", "b"))
        },
        conflicts = emptyMap(),
    )

    @Test
    fun newSurfaceEmitsCreateComponentsAndData() {
        assertEquals(
            listOf(
                A2uiMessage.CreateSurface("lunch", NOTIONS_CATALOG_ID),
                A2uiMessage.UpdateComponents("lunch", listOf(root, title)),
                A2uiMessage.UpdateDataModel("lunch", "/", view.data),
            ),
            diff(null, view),
        )
    }

    @Test
    fun changedComponentEmitsOnlyThatComponent() {
        val retitled = text("title", "Lunch at noon?")
        assertEquals(
            listOf(A2uiMessage.UpdateComponents("lunch", listOf(retitled))),
            diff(view, view.copy(components = view.components + ("title" to retitled))),
        )
    }

    @Test
    fun changedDataKeyEmitsOneUpdateDataModelAtItsPath() {
        val next = view.copy(data = JsonObject(view.data + ("poll" to buildJsonObject { put("question", "Where?"); put("votes", 3) })))
        assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", "/poll/votes", JsonPrimitive(3))), diff(view, next))
    }

    @Test
    fun closedSurfaceEmitsDeleteSurface() {
        assertEquals(listOf(A2uiMessage.DeleteSurface("lunch")), diff(view, null))
    }

    @Test
    fun reopenedSurfaceEmitsCreateAfterDelete() {
        // Edited while closed, the surface comes back as it now is, from nothing.
        val edited = view.copy(data = buildJsonObject { put("order", ids("b")) })
        val messages = diff(view, null) + diff(null, edited)
        assertEquals(
            listOf(
                A2uiMessage.DeleteSurface("lunch"),
                A2uiMessage.CreateSurface("lunch", NOTIONS_CATALOG_ID),
                A2uiMessage.UpdateComponents("lunch", listOf(root, title)),
                A2uiMessage.UpdateDataModel("lunch", "/", buildJsonObject { put("order", ids("b")) }),
            ),
            messages,
        )
    }

    @Test
    fun changedArrayIdsReplaceTheWholeArray() {
        val next = view.copy(data = JsonObject(view.data + ("order" to ids("b", "a"))))
        assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", "/order", ids("b", "a"))), diff(view, next))
    }

    @Test
    fun conflictIsPublishedAndClearedUnderBee() {
        val answer = mapOf("/answer" to listOf(JsonPrimitive("x"), JsonPrimitive("y")))
        val both = answer + ("/poll/votes" to listOf(JsonPrimitive(2), JsonPrimitive(3)))
        val conflicted = view.copy(conflicts = answer)
        assertAll(
            // Conflicts are keyed by the raw pointer, escaped once when it becomes a path.
            {
                assertEquals(
                    listOf(
                        A2uiMessage.UpdateDataModel(
                            "lunch",
                            "/_bee",
                            buildJsonObject { put("conflicts", buildJsonObject { put("/answer", ids("x", "y")) }) },
                        ),
                    ),
                    diff(view, conflicted),
                )
            },
            {
                assertEquals(
                    listOf(A2uiMessage.UpdateDataModel("lunch", "/_bee/conflicts/~1poll~1votes", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(3))))),
                    diff(conflicted, view.copy(conflicts = both)),
                )
            },
            { assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", "/_bee/conflicts/~1answer", null)), diff(view.copy(conflicts = both), view.copy(conflicts = both - "/answer"))) },
            { assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", "/_bee", null)), diff(conflicted, view)) },
        )
    }

    @Test
    fun nullIsAbsentInCreateAndInDiffs() {
        val nulled = view.copy(data = buildJsonObject { put("poll", buildJsonObject { put("question", JsonNull); put("votes", 2) }) })
        val created = diff(null, nulled).filterIsInstance<A2uiMessage.UpdateDataModel>().single()
        assertAll(
            { assertEquals(buildJsonObject { put("poll", buildJsonObject { put("votes", 2) }) }, created.value) },
            {
                assertEquals(
                    listOf(
                        A2uiMessage.UpdateDataModel("lunch", "/order", null),
                        A2uiMessage.UpdateDataModel("lunch", "/poll/question", null),
                    ),
                    diff(view, nulled),
                )
            },
        )
    }

    private fun text(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("component", "Text")
        put("text", text)
    }

    private fun ids(vararg ids: String) = JsonArray(ids.map(::JsonPrimitive))
}
