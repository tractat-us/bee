package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The `checkin` prompt as a session meets it, beside `whiteboard`: listed with an optional focus,
 * its text naming the card's surfaceId rule and the wait, and its example card opening and taking a
 * checkpoint's update. Each test drives [BeeServer] over an in-process MCP client, as
 * [WhiteboardGuideTest] does.
 */
class CheckinGuideTest {
    private val options = RequestOptions(timeout = 5.minutes)

    @Test
    fun checkinIsListedBesideWhiteboardWithAnOptionalFocus() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val prompts = ada.mcp.listPrompts(options = options).prompts
        val checkin = prompts.single { it.name == CheckinGuide.PROMPT }
        val argument = checkin.arguments.orEmpty().single()
        assertAll(
            { assertEquals(setOf(WhiteboardGuide.PROMPT, CheckinGuide.PROMPT), prompts.map { it.name }.toSet()) },
            { assertEquals(CheckinGuide.FOCUS, argument.name) },
            { assertEquals(false, argument.required) },
        )
        rig.close()
    }

    /** The text names the repo-derived surfaceId and its suffix rule, the one card it updates, the wait, the stop, and the sharing rule. */
    @Test
    fun checkinNamesTheRepoSurfaceIdAndTheWait() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val withFocus = ada.promptText(mapOf(CheckinGuide.FOCUS to "retry-test"))
        val without = ada.promptText(null)
        val blank = ada.promptText(mapOf(CheckinGuide.FOCUS to "  "))
        assertAll(
            { assertTrue("git rev-parse --path-format=absolute --git-common-dir" in without, "the repo is the shared .git's directory") },
            { assertTrue("<id>-2, <id>-3" in without && "open or closed" in without, "a taken id is suffixed") },
            { assertTrue("\"<repo> — <task>\"" in without, "the title names repo and task") },
            { assertTrue("${WaitTool.WAIT} with your surfaceId and timeoutSeconds 20" in without, "a decision waits on the card") },
            { assertTrue("${BeeTools.UPDATE} the same card, never a new surface" in without, "checkpoints update in place") },
            { assertTrue("\"done\" or \"pause\" record ends the check-ins" in without, "Done and Pause stop it") },
            { assertTrue("plain HTTP" in without && "never a secret" in without, "it shares summaries only") },
            { assertTrue(CheckinGuide.CARD_COMPONENTS in without && CheckinGuide.CARD_DATA in without, "the card is shown verbatim") },
            { assertTrue("typed after the command: \"retry-test\"" in withFocus, withFocus) },
            { assertTrue("waiting for a task" in without && "waiting for a task" !in withFocus, "no focus asks on the card") },
            { assertEquals(without, blank, "a blank focus is no focus") },
        )
        rig.close()
    }

    /** The example card opens as the prompt shows it, and a checkpoint's update, new lines and no question, applies to it. */
    @Test
    fun theCardOpensAndTakesACheckpoint() = runTest(timeout = 120.seconds) {
        val rig = JoinRig(this).apply { start() }
        val ada = rig.session("ada")

        val opened = ada.call(
            BeeTools.OPEN,
            mapOf(
                "surfaceId" to "bee",
                "title" to "bee — fix the flaky retry test",
                "components" to Json.parseToJsonElement(CheckinGuide.CARD_COMPONENTS).jsonArray,
                "data" to Json.parseToJsonElement(CheckinGuide.CARD_DATA).jsonObject,
            ),
        )
        val checkpoint = ada.call(
            BeeTools.UPDATE,
            mapOf(
                "surfaceId" to "bee",
                "data" to Json.parseToJsonElement("""{"/now": "Running the suite"}"""),
                "components" to Json.parseToJsonElement("""[{"id":"decision","component":"Column","children":[]}]""").jsonArray,
            ),
        )
        // Control: a card missing a child it names is refused, so a green open above means something.
        val broken = CheckinGuide.CARD_COMPONENTS.replace("""{"id":"done-label",""", """{"id":"done-text",""")
        val refused = ada.call(
            BeeTools.OPEN,
            mapOf("surfaceId" to "control", "title" to "control", "components" to Json.parseToJsonElement(broken).jsonArray),
        )
        val card = ada.call(BeeTools.BOARD, emptyMap()).json().getValue("open").jsonArray.map { it.jsonObject }
            .single { it.getValue("surfaceId").jsonPrimitive.content == "bee" }
        val decision = card.getValue("components").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "decision" }
        assertAll(
            { assertEquals(null, opened.isError, opened.text()) },
            { assertEquals(null, checkpoint.isError, checkpoint.text()) },
            { assertEquals("Running the suite", card.getValue("data").jsonObject.getValue("now").jsonPrimitive.content) },
            { assertEquals(0, decision.getValue("children").jsonArray.size, "no question left on the card") },
            { assertTrue(refused.isError == true, "control: a card naming a missing child is refused: ${refused.text()}") },
        )
        rig.close()
    }

    private suspend fun Session.promptText(arguments: Map<String, String>?): String =
        mcp.getPrompt(GetPromptRequest(GetPromptRequestParams(CheckinGuide.PROMPT, arguments)), options)
            .messages.map { it.content }.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}
