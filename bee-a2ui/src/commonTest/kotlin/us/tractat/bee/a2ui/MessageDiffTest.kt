package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val messages = diff(view, null) + diff(null, view)
        assertEquals(listOf(A2uiMessage.DeleteSurface("lunch")) + diff(null, view), messages)
        assertEquals(A2uiMessage.CreateSurface("lunch", NOTIONS_CATALOG_ID), messages[1])
    }

    @Test
    fun changedArrayIdsReplaceTheWholeArray() {
        val next = view.copy(data = JsonObject(view.data + ("order" to ids("b", "a"))))
        assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", "/order", ids("b", "a"))), diff(view, next))
    }

    @Test
    fun conflictIsPublishedAndClearedUnderBee() {
        val conflicted = view.copy(conflicts = mapOf("/answer" to listOf(JsonPrimitive("x"), JsonPrimitive("y"))))
        val path = "/_bee"
        assertEquals(
            listOf(
                A2uiMessage.UpdateDataModel(
                    "lunch",
                    path,
                    buildJsonObject { put("conflicts", buildJsonObject { put("~1answer", ids("x", "y")) }) },
                ),
            ),
            diff(view, conflicted),
        )
        assertEquals(listOf(A2uiMessage.UpdateDataModel("lunch", path, null)), diff(conflicted, view))
    }

    private fun text(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("component", "Text")
        put("text", text)
    }

    private fun ids(vararg ids: String) = JsonArray(ids.map(::JsonPrimitive))
}
