package us.tractat.bee.host.fixtures

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.SurfaceValidator
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Notions fixtures for e2e/notions.spec.mjs, one surface per test: polls whose question and
 * options are appended entries (A1), boards whose columns and cards are ordered collections
 * (`{items, order}`, A12), and `markup`, which puts an `<img onerror>` in every label.
 */
internal val NOTIONS_FIXTURES: List<String> =
    listOf("poll-tabs", "poll-basis", "poll-history", "board-drop", "board-race", "markup")

private const val MARKUP = "<img src=x onerror=window.pwned=1>"

/** Opens Notions fixture [id] as [author], its appended entries one write each. */
internal suspend fun seedNotion(writer: BoardClient, author: ReplicaId, id: String, now: Instant) {
    val meta = SurfaceMeta("Fixture $id", NOTIONS_CATALOG_ID, PeerIdentity.human("e2e"), now)
    val (components, data, appends) = when (id) {
        "poll-tabs", "poll-history" -> poll("Lunch?", "pizza", "salad")
        "poll-basis" -> poll("Tea or coffee?", "tea", "coffee")
        "board-drop", "board-race" -> board("Write the spec")
        "markup" -> markup()
        else -> error("No Notions fixture $id")
    }
    writer.mutate { it.openSurface(author, id, meta, components, data) }
    for ((pointer, value) in appends) writer.mutate { it.appendEntry(author, id, pointer, JsonPrimitive(value)) }
}

/** Whether [state] holds Notions fixture [id] as seeded: its appended entries arrive last. */
internal fun notionSeeded(state: BoardState, id: String): Boolean {
    val data = state.surfaceDoc(id)?.toSurfaceView(id)?.data ?: return false
    return when {
        id.startsWith("board") -> data["cards"] != null
        else -> (data["options"] as? JsonArray)?.size == (if (id == "markup") 1 else 2)
    }
}

/**
 * The mid-test acts e2e/notions.spec.mjs asks of another peer, one per seeder run:
 * `--close <surfaceId>` ("clear the board", as the bridge's `bee_board_clear` closes each surface)
 * and `--append-option <surfaceId>=<text>`. Returns the check that a fresh reader has it, or null
 * when [flags] name neither.
 */
internal suspend fun actOnNotion(writer: BoardClient, author: ReplicaId, flags: Map<String, String?>, now: Instant): ((BoardState) -> Boolean)? {
    flags["--close"]?.let { id ->
        held(writer, id)
        writer.mutate { it.closeSurface(author, id, now) }
        return { state -> state.meta(id)?.closedAt != null }
    }
    flags["--append-option"]?.let { arg ->
        val (id, text) = arg.split("=", limit = 2).also { require(it.size == 2) { "--append-option takes <surfaceId>=<text>" } }
        held(writer, id)
        val before = writer.state.value.surfaceDoc(id)?.toSurfaceView(id)?.data?.get("options") as? JsonArray
        writer.mutate { it.appendEntry(author, id, "/options", JsonPrimitive(text)) }
        return { state -> ((state.surfaceDoc(id)?.toSurfaceView(id)?.data?.get("options") as? JsonArray)?.size ?: 0) > (before?.size ?: 0) }
    }
    return null
}

/** Waits for [writer]'s replica to hold surface [id]: a write to a surface it lacks is refused. */
private suspend fun held(writer: BoardClient, id: String) {
    withTimeout(30.seconds) { writer.state.first { it.surfaceDoc(id) != null } }
}

private data class Fixture(val components: List<JsonObject>, val data: JsonObject, val appends: List<Pair<String, String>>)

private fun poll(question: String, vararg options: String) = Fixture(
    components = listOf(pollComponent("root")),
    data = buildJsonObject { putJsonObject("answers") {} },
    appends = listOf("/question" to question) + options.map { "/options" to it },
)

private fun board(label: String) = Fixture(
    components = listOf(boardComponent("root")),
    data = boardData(
        columns = listOf("todo" to "To do", "doing" to "Doing", "done" to "Done"),
        cards = listOf("c1" to label, "c2" to "Review it"),
    ),
    appends = emptyList(),
)

private fun markup() = Fixture(
    components = listOf(
        buildJsonObject {
            put("id", "root")
            put("component", "Column")
            put("children", JsonArray(listOf(JsonPrimitive("poll"), JsonPrimitive("board"))))
        },
        pollComponent("poll"),
        boardComponent("board"),
    ),
    data = buildJsonObject {
        putJsonObject("answers") {}
        boardData(columns = listOf("todo" to MARKUP), cards = listOf("c1" to MARKUP)).forEach { (k, v) -> put(k, v) }
    },
    appends = listOf("/question" to MARKUP, "/options" to MARKUP),
)

private fun pollComponent(id: String) = buildJsonObject {
    put("id", id)
    put("component", SurfaceValidator.POLL)
    putJsonObject("question") { put("path", "/question") }
    putJsonObject("options") { put("path", "/options") }
    putJsonObject("answers") { put("path", "/answers") }
}

private fun boardComponent(id: String) = buildJsonObject {
    put("id", id)
    put("component", SurfaceValidator.BOARD)
    putJsonObject("columns") { put("path", "/columns") }
    putJsonObject("cards") { put("path", "/cards") }
}

/** Columns and cards as ordered collections; every card starts in the first column. */
private fun boardData(columns: List<Pair<String, String>>, cards: List<Pair<String, String>>) = buildJsonObject {
    putJsonObject("columns") {
        putJsonObject("items") { columns.forEach { (id, title) -> putJsonObject(id) { put("title", title) } } }
        put("order", JsonArray(columns.map { JsonPrimitive(it.first) }))
    }
    putJsonObject("cards") {
        putJsonObject("items") {
            cards.forEach { (id, label) ->
                putJsonObject(id) {
                    put("label", label)
                    put("column", columns.first().first)
                }
            }
        }
        put("order", JsonArray(cards.map { JsonPrimitive(it.first) }))
    }
}
