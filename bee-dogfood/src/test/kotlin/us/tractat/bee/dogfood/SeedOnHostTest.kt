package us.tractat.bee.dogfood

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.host.BeeHost
import us.tractat.bee.host.HostConfig
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The seeder against a real [BeeHost], in process, over the host's own HTTP MCP endpoint: the path
 * `bin/bee-dogfood-seed` takes. A first seed opens the three surfaces, a second changes nothing, and
 * a changed plan redraws the Plan in place, under the same id, without the dropped card. A card
 * dragged on the board moves back at the next seed: the files are the source of truth.
 */
class SeedOnHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-dogfood-host-")
    private val plan = FixturePlan.parse()

    @AfterTest
    fun stop() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun seedingOpensThenLeavesAloneThenRedrawsInPlace() = runBlocking {
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir.resolve("host")))
        try {
            val url = "http://127.0.0.1:${host.port}"
            val surfaces = listOf(
                PlanBoard.surface(plan),
                MorningReport.surface("# Night\n\n## TL;DR\n- All green.", "morning-report.md"),
                Decisions.surface(Decisions.parse("""[{"number":7,"title":"Pick one?","url":"https://github.com/tractat-us/bee/issues/7","labels":[{"name":"decision-needed"}],"body":""}]""")),
            )
            val dropped = FixturePlan.NEXT
            val changed = PlanBoard.surface(JsonObject(plan + ("items" to JsonObject(plan.obj("items") - dropped))))

            val first = seed(url, surfaces)
            val second = seed(url, surfaces)
            val third = seed(url, listOf(changed))
            val board = withTimeout(10.seconds) { host.state.first { PlanBoard.SURFACE_ID in it.openSurfaces() } }
            val key = changed.data.keys.single()
            val read = readBoard(url) { open(it).any { surface -> surface.string("surfaceId") == PlanBoard.SURFACE_ID && key in surface.obj("data") } }
            val planNow = open(read).single { it.string("surfaceId") == PlanBoard.SURFACE_ID }
            val cards = planNow.obj("data").obj(key).obj("cards").obj("items")
            val ids = open(read).map { it.string("surfaceId") }
            assertAll(
                { assertEquals(surfaces.associate { it.id to Outcome.Opened }, first, "a first seed opens all three") },
                { assertEquals(surfaces.associate { it.id to Outcome.Unchanged }, second, "a second seed changes nothing") },
                { assertEquals(mapOf(PlanBoard.SURFACE_ID to Outcome.Updated), third, "a changed plan redraws the Plan") },
                { assertEquals(surfaces.map { it.id }.sorted(), ids.sorted(), "one surface each, never duplicated") },
                { assertTrue(dropped !in cards && cards.isNotEmpty(), "the redrawn Plan has no card for the dropped item") },
                { assertEquals("dogfood-claude", board.meta(PlanBoard.SURFACE_ID)?.openedBy?.name, "drawn by the dogfood agent") },
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun aDraggedCardMovesBackAtTheNextSeed() = runBlocking {
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir.resolve("host")))
        try {
            val url = "http://127.0.0.1:${host.port}"
            val surface = PlanBoard.surface(plan)
            val key = surface.data.keys.single()
            val card = FixturePlan.IN_FLIGHT
            val column = { board: JsonObject ->
                open(board).singleOrNull { it.string("surfaceId") == PlanBoard.SURFACE_ID }
                    ?.let { listOf("data", key, "cards", "items", card).fold<String, JsonObject?>(it) { node, k -> node?.get(k) as? JsonObject } }
                    ?.stringOrNull("column")
            }

            val first = seed(url, listOf(surface))
            // A page's drop writes the card's column leaf (NotionActions.drop); a session writes the same leaf.
            drag(url, "/$key/cards/items/$card/column", "next")
            readBoard(url) { column(it) == "next" }
            val second = seed(url, listOf(surface))
            val after = readBoard(url, orLatest = true) { column(it) == "in-flight" }
            assertAll(
                { assertEquals(mapOf(PlanBoard.SURFACE_ID to Outcome.Opened), first) },
                { assertEquals(mapOf(PlanBoard.SURFACE_ID to Outcome.Updated), second, "a dragged card is a change to redraw") },
                { assertEquals("in-flight", column(after), "the card is back in its plan column") },
            )
        } finally {
            host.close()
        }
    }

    private suspend fun drag(url: String, pointer: String, column: String) {
        val tools = McpBoardTools.join(url, "page")
        try {
            readBoard(tools) { open(it).any { surface -> surface.string("surfaceId") == PlanBoard.SURFACE_ID } }
            tools.call(
                Seeder.UPDATE,
                mapOf("surfaceId" to JsonPrimitive(PlanBoard.SURFACE_ID), "data" to buildJsonObject { put(pointer, column) }),
            )
        } finally {
            tools.leave()
        }
    }

    private suspend fun seed(url: String, surfaces: List<SeedSurface>): Map<String, Outcome> {
        val tools = McpBoardTools.join(url, "dogfood")
        return try {
            Seeder(tools).seed(surfaces)
        } finally {
            tools.leave()
        }
    }

    /**
     * Reads the board through bee_board as a separate session, until [ready] holds: its replica
     * catches up. After 10 s it fails, or with [orLatest] returns the last board it read, so the
     * caller's assertion says what was there instead of timing out.
     */
    private suspend fun readBoard(url: String, orLatest: Boolean = false, ready: (JsonObject) -> Boolean): JsonObject {
        val tools = McpBoardTools.join(url, "reader")
        return try {
            readBoard(tools, orLatest, ready)
        } finally {
            tools.leave()
        }
    }

    private suspend fun readBoard(tools: BoardTools, orLatest: Boolean = false, ready: (JsonObject) -> Boolean): JsonObject {
        var board = tools.call(Seeder.BOARD, emptyMap())
        val held = withTimeoutOrNull(10.seconds) {
            while (!ready(board)) {
                delay(100.milliseconds)
                board = tools.call(Seeder.BOARD, emptyMap())
            }
        }
        check(held != null || orLatest) { "The board never got there in 10 s. Last read: $board" }
        return board
    }

    private fun open(board: JsonObject): List<JsonObject> = board.getValue("open").jsonArray.map { it.jsonObject }
}
