package us.tractat.bee.dogfood

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import us.tractat.bee.host.BeeHost
import us.tractat.bee.host.HostConfig
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The seeder against a real [BeeHost], in process, over the host's own HTTP MCP endpoint: the path
 * `bin/bee-dogfood-seed` takes. A first seed opens the three surfaces, a second changes nothing, and
 * a changed plan redraws the Plan in place, under the same id, without the dropped card.
 */
class SeedOnHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-dogfood-host-")
    private val plan = PlanYaml.parse(Path("../docs/project/plan.yaml").readText())

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
            val dropped = "bee-21"
            val changed = PlanBoard.surface(JsonObject(plan + ("items" to JsonObject(plan.obj("items") - dropped))))

            val first = seed(url, surfaces)
            val second = seed(url, surfaces)
            val third = seed(url, listOf(changed))
            val board = withTimeout(10.seconds) { host.state.first { PlanBoard.SURFACE_ID in it.openSurfaces() } }
            val read = readBoard(url)
            val planNow = read.getValue("open").jsonArray.map { it.jsonObject }.single { it.string("surfaceId") == PlanBoard.SURFACE_ID }
            val key = changed.data.keys.single()
            val cards = planNow.obj("data").obj(key).obj("cards").obj("items")
            val ids = read.getValue("open").jsonArray.map { it.jsonObject.string("surfaceId") }
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

    private suspend fun seed(url: String, surfaces: List<SeedSurface>): Map<String, Outcome> {
        val tools = McpBoardTools.join(url, "dogfood")
        return try {
            Seeder(tools).seed(surfaces)
        } finally {
            tools.leave()
        }
    }

    private suspend fun readBoard(url: String): JsonObject {
        val tools = McpBoardTools.join(url, "reader")
        return try {
            tools.call(Seeder.BOARD, emptyMap())
        } finally {
            tools.leave()
        }
    }
}
