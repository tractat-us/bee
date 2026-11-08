package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a fresh session learns from the bridge alone: the [WhiteboardGuide]'s instructions in
 * `initialize`, the `whiteboard` prompt, and a cheat-sheet whose every surface opens. Each test
 * drives [BeeServer] over an in-process MCP client, as [BeeToolsTest] does.
 */
class WhiteboardGuideTest {
    private val options = RequestOptions(timeout = 5.minutes)

    @Test
    fun initializeCarriesTheInstructions() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val sent = ada.mcp.serverInstructions.orEmpty()
        assertAll(
            { assertEquals(WhiteboardGuide.INSTRUCTIONS, sent) },
            { assertTrue(WhiteboardGuide.EXAMPLES.all { it.components in sent }, "every cheat-sheet surface is shown verbatim") },
        )
        rig.close()
    }

    /** Each cheat-sheet entry opens through `bee_surface_open`, the validator a session's own surfaces meet. */
    @Test
    fun everyCheatSheetSurfaceOpens() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val ids = WhiteboardGuide.EXAMPLES.indices.map { "example-$it" }

        val opened = WhiteboardGuide.EXAMPLES.zip(ids).map { (example, id) -> example to ada.call(BeeTools.OPEN, example.args(id)) }
        // Control: the rig refuses a surface the validator can't pass, so a green result above means something.
        val rootless = WhiteboardGuide.EXAMPLES.first().let { it.copy(components = it.components.replace("\"root\"", "\"top\"")) }
        val refused = ada.call(BeeTools.OPEN, rootless.args("control"))
        val each = opened.map { (example, result) -> { assertEquals(null, result.isError, "${example.name}: ${result.text()}") } }
        assertAll(
            *each.toTypedArray(),
            { assertEquals(ids, ada.client.state.value.openSurfaces().sorted(), "every example is on the board") },
            { assertTrue(refused.isError == true, "control: a rootless surface is refused: ${refused.text()}") },
        )
        rig.close()
    }

    /** Every `bee_…` name the guide's texts use is a tool the server serves, so a rename can't strand them. */
    @Test
    fun everyToolTheGuideNamesIsServed() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val served = ada.mcp.listTools(options = options).tools.map { it.name }.toSet()
        val texts = listOf(WhiteboardGuide.INSTRUCTIONS, WhiteboardGuide.prompt(null), WhiteboardGuide.prompt("a topic"))
        val named = texts.flatMap { Regex("""\bbee_[a-z_]+""").findAll(it).map { m -> m.value }.toList() }.toSet()
        assertAll(
            { assertTrue(named.containsAll(setOf(WaitTool.WAIT, BeeTools.DONE, BeeTools.CLAIM)), "the scan finds tool names: $named") },
            { assertEquals(emptySet(), named - served, "named but not served") },
        )
        rig.close()
    }

    @Test
    fun whiteboardPromptIsListedWithAnOptionalTopic() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val prompt = ada.mcp.listPrompts(options = options).prompts.single()
        val argument = prompt.arguments.orEmpty().single()
        assertAll(
            { assertEquals(WhiteboardGuide.PROMPT, prompt.name) },
            { assertEquals(WhiteboardGuide.TOPIC, argument.name) },
            { assertEquals(false, argument.required) },
        )
        rig.close()
    }

    /** With a topic the prompt draws it; without one, or with a blank one, it asks on the board. */
    @Test
    fun whiteboardPromptDrawsTheTopicOrAsksForOne() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val withTopic = ada.promptText(mapOf(WhiteboardGuide.TOPIC to "the login flow"))
        val without = ada.promptText(null)
        val blank = ada.promptText(mapOf(WhiteboardGuide.TOPIC to "  "))
        assertAll(
            { assertTrue("Draw the topic: the login flow." in withTopic, withTopic) },
            { assertTrue("Ask, on the board, what to work on" !in withTopic, "a topic isn't asked for") },
            { assertTrue("Ask, on the board, what to work on" in without, without) },
            { assertEquals(without, blank, "a blank topic is no topic") },
            { assertTrue("bee_wait_action" in without && "Done" in without, "it loops on waits until done") },
        )
        rig.close()
    }

    private suspend fun Session.promptText(arguments: Map<String, String>?): String =
        mcp.getPrompt(GetPromptRequest(GetPromptRequestParams(WhiteboardGuide.PROMPT, arguments)), options)
            .messages.map { it.content }.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun WhiteboardGuide.Example.args(id: String): Map<String, Any?> = mapOf(
        "surfaceId" to id,
        "title" to name,
        "components" to Json.parseToJsonElement(components).jsonArray,
        "data" to (data?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())),
    )
}
