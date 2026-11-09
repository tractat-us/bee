package us.tractat.bee.dogfood

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionsTest {
    private val gh = """
        [{"number":150,"title":"Does \"infinite whiteboard\" mean a pan-and-zoom canvas?","url":"https://github.com/tractat-us/bee/issues/150",
          "labels":[{"name":"needs-design"}],
          "body":"> 🤖 Filed by Claude? on behalf of @keddie.\n\nToday the board is a page: surfaces stack (spec §6.1, `BoardView.kt`). Does it mean an endless canvas, panned and zoomed? Or not?"},
         {"number":131,"title":"Which bee modules go to Maven Central, and when","url":"https://github.com/tractat-us/bee/issues/131",
          "labels":[{"name":"decision-needed"},{"name":"needs-design"}],"body":"**Context.** No question here."}]
    """.trimIndent()

    @Test
    fun ghsListBecomesOneCardPerDecisionNewestFirstDeduplicated() {
        val decisions = Decisions.parse(gh)
        val surface = Decisions.surface(decisions + decisions)
        val root = surface.components.first { it.string("id") == SeedSurface.ROOT }
        val text150 = surface.components.first { it.string("id") == "d-150-text" }.string("text")
        val text131 = surface.components.first { it.string("id") == "d-131-text" }.string("text")
        assertAll(
            { assertEquals(listOf("d-150", "d-131"), root.getValue("children").jsonArray.map { it.jsonPrimitive.content }) },
            {
                assertEquals(
                    "**[#150](https://github.com/tractat-us/bee/issues/150) Does \"infinite whiteboard\" mean a pan-and-zoom canvas?** · design\n\n" +
                        "Does it mean an endless canvas, panned and zoomed?",
                    text150,
                )
            },
            { assertEquals("**[#131](https://github.com/tractat-us/bee/issues/131) Which bee modules go to Maven Central, and when** · decide", text131, "decide wins; no question asked") },
        )
    }

    @Test
    fun aQuestionIsTheFirstAskedSentenceOutsideQuotesAndBold() {
        assertAll(
            { assertEquals("Who places a surface?", Decisions.question("> Is this quoted?\n\n- **Who places a surface?** The agent.")) },
            { assertNull(Decisions.question("Nothing asked.")) },
            { assertEquals(Decisions.QUESTION_CHARS, Decisions.question("a".repeat(300) + "?")!!.length) },
        )
    }

    @Test
    fun noDecisionsSaysSoAndAnOutageSaysWhy() {
        val none = Decisions.surface(emptyList()).components.first { it.string("id") == "none" }.string("text")
        val down = Decisions.surface(emptyList(), "Couldn't ask GitHub.").components.first { it.string("id") == "none" }.string("text")
        assertAll(
            { assertEquals("No open issue or PR is labelled `decision-needed` or `needs-design`.", none) },
            { assertEquals("Couldn't ask GitHub.", down) },
        )
    }
}
