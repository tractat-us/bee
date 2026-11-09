package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `docs/tools.md`, the reference a person reads, names exactly the tools and prompts the bridge
 * serves: a tool or prompt added, renamed or removed without the page fails here. Gradle runs a
 * module's tests from the module's directory, and `bee-mcp/build.gradle.kts` declares the page as
 * this task's input, so an edit to it reruns the check.
 */
class ToolsPageTest {
    private val options = RequestOptions(timeout = 5.minutes)
    private val page = File("../docs/tools.md")

    @Test
    fun theToolsPageNamesEveryServedToolAndPrompt() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val servedTools = ada.mcp.listTools(options = options).tools.map { it.name }.toSet()
        val servedPrompts = ada.mcp.listPrompts(options = options).prompts.map { it.name }.toSet()
        val rows = page.readLines().filter { it.startsWith("| `") }
        val pageTools = rows.mapNotNull { Regex("""^\| `(bee_[a-z_]+)`""").find(it)?.groupValues?.get(1) }
        val pagePrompts = rows.mapNotNull { Regex("""^\| `/mcp__bee__([a-z_]+)""").find(it)?.groupValues?.get(1) }
        assertAll(
            { assertTrue(servedTools.isNotEmpty() && servedPrompts.isNotEmpty(), "the bridge serves tools and prompts") },
            { assertEquals(servedTools, pageTools.toSet(), "tools on ${page.path} vs served") },
            { assertEquals(servedPrompts, pagePrompts.toSet(), "prompts on ${page.path} vs served") },
            { assertEquals(pageTools.size + pagePrompts.size, (pageTools + pagePrompts).toSet().size, "a row listed twice") },
        )
        rig.close()
    }
}
