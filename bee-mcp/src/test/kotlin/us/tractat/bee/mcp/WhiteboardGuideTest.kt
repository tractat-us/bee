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
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.bee.a2ui.SurfaceValidator
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
        val shown = WhiteboardGuide.EXAMPLES.filter { it.components in sent }
        assertAll(
            { assertEquals(WhiteboardGuide.INSTRUCTIONS, sent) },
            { assertEquals(WhiteboardGuide.ESSENTIAL, shown, "the essential surfaces are shown verbatim, and only they") },
            { assertEquals(SurfaceValidator.SKETCH, shown.first().components.componentType(), "Sketch comes first") },
        )
        rig.close()
    }

    /**
     * Claude Code cuts server instructions past `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`, 4096
     * characters by default, and appends "… [truncated]": anything past it never reaches the model.
     */
    @Test
    fun instructionsFitClaudeCodesLimit() {
        assertTrue(
            WhiteboardGuide.INSTRUCTIONS.length <= WhiteboardGuide.CLAUDE_CODE_TEXT_LIMIT,
            "instructions are ${WhiteboardGuide.INSTRUCTIONS.length} characters, over ${WhiteboardGuide.CLAUDE_CODE_TEXT_LIMIT}",
        )
    }

    /** `bee_examples` returns the whole cheat-sheet, every entry verbatim, and its description fits the cut too. */
    @Test
    fun beeExamplesReturnsTheWholeCheatSheet() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val tool = ada.mcp.listTools(options = options).tools.single { it.name == WhiteboardGuide.EXAMPLES_TOOL }
        val result = ada.call(WhiteboardGuide.EXAMPLES_TOOL, emptyMap())
        val missing = WhiteboardGuide.EXAMPLES.filter { e -> listOfNotNull(e.components, e.data, e.append).any { it !in result.text() } }
        assertAll(
            { assertEquals(null, result.isError, result.text()) },
            { assertEquals(emptyList(), missing.map { it.name }, "cheat-sheet entries missing from the result") },
            { assertTrue(tool.description.orEmpty().length <= WhiteboardGuide.CLAUDE_CODE_TEXT_LIMIT, "description fits") },
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

    /** The updates the cheat-sheet describes apply: a field's text by pointer, a card added to the Board, an option appended to the Poll. */
    @Test
    fun theCheatSheetsUpdatesApply() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")
        val field = WhiteboardGuide.EXAMPLES.single { "TextField" in it.components }
        val board = WhiteboardGuide.EXAMPLES.single { "\"Board\"" in it.components }
        val poll = WhiteboardGuide.EXAMPLES.single { "\"Poll\"" in it.components }
        ada.call(BeeTools.OPEN, field.args("field"))
        ada.call(BeeTools.OPEN, board.args("board"))
        ada.call(BeeTools.OPEN, poll.args("poll"))
        val option = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "poll", "append" to Json.parseToJsonElement("""{"/options": ["Passwords"]}""")))

        val typed = ada.call(BeeTools.UPDATE, mapOf("surfaceId" to "field", "data" to Json.parseToJsonElement("""{"/idea": "new text"}""")))
        val added = ada.call(
            BeeTools.UPDATE,
            mapOf(
                "surfaceId" to "board",
                "data" to Json.parseToJsonElement("""{"/cards/items/c2": {"label": "Pick a host", "column": "todo"}, "/cards/order": ["c1", "c2"]}"""),
            ),
        )
        val boardData = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("open").jsonArray
            .map { it.jsonObject }.associate { it.getValue("surfaceId").jsonPrimitive.content to it.getValue("data").jsonObject }
        val cards = boardData.getValue("board").getValue("cards").jsonObject
        assertAll(
            { assertEquals(null, typed.isError, typed.text()) },
            { assertEquals(null, added.isError, added.text()) },
            { assertEquals("new text", boardData.getValue("field").getValue("idea").jsonPrimitive.content) },
            { assertEquals(setOf("c1", "c2"), cards.getValue("items").jsonObject.keys) },
            { assertEquals(listOf("c1", "c2"), cards.getValue("order").jsonArray.map { it.jsonPrimitive.content }) },
            { assertEquals(null, option.isError, option.text()) },
            {
                val options = boardData.getValue("poll").getValue("options").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("SSO", "Passkeys", "Passwords"), options)
            },
        )
        rig.close()
    }

    /** Every `bee_…` name the guide's texts use is a tool the server serves, so a rename can't strand them. */
    @Test
    fun everyToolTheGuideNamesIsServed() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val served = ada.mcp.listTools(options = options).tools.map { it.name }.toSet()
        val texts = listOf(WhiteboardGuide.INSTRUCTIONS, WhiteboardGuide.CHEAT_SHEET, WhiteboardGuide.prompt(null), WhiteboardGuide.prompt("a topic"))
        val named = texts.flatMap { Regex("""\bbee_[a-z_]+""").findAll(it).map { m -> m.value }.toList() }.toSet()
        assertAll(
            { assertTrue(named.containsAll(setOf(WaitTool.WAIT, BeeTools.DONE, BeeTools.CLAIM, WhiteboardGuide.EXAMPLES_TOOL)), "the scan finds tool names: $named") },
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

        val withTopic = ada.promptText(mapOf(WhiteboardGuide.TOPIC to "login-flow"))
        val without = ada.promptText(null)
        val blank = ada.promptText(mapOf(WhiteboardGuide.TOPIC to "  "))
        assertAll(
            { assertTrue("typed after the command: \"login-flow\"" in withTopic, withTopic) },
            { assertTrue("Ask, on the board, what to work on" !in withTopic, "a topic isn't asked for") },
            { assertTrue("Ask, on the board, what to work on" in without, without) },
            { assertEquals(without, blank, "a blank topic is no topic") },
            { assertTrue("bee_wait_action" in without && "Done" in without, "it loops on waits until done") },
            { assertTrue("no person attached" in without && "10 minutes" in without, "it stops when the room is idle") },
        )
        rig.close()
    }

    private fun String.componentType(): String =
        Json.parseToJsonElement(this).jsonArray.first().jsonObject.getValue("component").jsonPrimitive.content

    private suspend fun Session.promptText(arguments: Map<String, String>?): String =
        mcp.getPrompt(GetPromptRequest(GetPromptRequestParams(WhiteboardGuide.PROMPT, arguments)), options)
            .messages.map { it.content }.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun WhiteboardGuide.Example.args(id: String): Map<String, Any?> = mapOf(
        "surfaceId" to id,
        "title" to name,
        "components" to Json.parseToJsonElement(components).jsonArray,
        "data" to (data?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())),
        "append" to (append?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())),
    )
}
