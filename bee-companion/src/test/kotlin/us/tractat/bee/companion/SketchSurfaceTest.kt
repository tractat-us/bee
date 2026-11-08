package us.tractat.bee.companion

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import us.tractat.bee.companion.CompanionRig.Companion.WAIT
import us.tractat.bee.companion.CompanionRig.Companion.sketchHtml
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SketchSurfaceTest {
    /**
     * Review Focus 5: a `Sketch` over the board link's payload budget is refused with a message
     * that states the limit, nothing reaches the board, and the board keeps working: the next
     * screen that fits is shown.
     *
     * The links publish an 8 KB frame ceiling, as a framed transport would. Without the refusal,
     * the seam would drop the oversized delta without a word: the companion's replica would hold
     * the screen and no other peer would ever see it.
     */
    @Test
    fun oversizedSketchIsRefusedWithItsLimit() = runTest(timeout = 120.seconds) {
        val rig = CompanionRig(this, Files.createTempDirectory("w8-sketch-"), maxFrameBytes = LIMIT)
        rig.start()
        rig.screen("small.html", "<p>small</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "small" in it }
        val budget = assertNotNull(companion.client.maxPayloadBytes, "rig: the link publishes a budget")

        rig.screen("huge.html", "<p>" + "x".repeat(4 * LIMIT) + "</p>")
        // Whatever the companion did with the screen, it reports it: shown, or refused.
        val refusal = withTimeout(WAIT) {
            while (companion.reports.none { it["file"] == JsonPrimitive("huge.html") }) delay(100)
            companion.reports.single { it["file"] == JsonPrimitive("huge.html") }
        }
        val onBoard = companion.client.state.value
        rig.screen("next.html", "<p>next</p>")
        rig.shows(browser) { "next" in it }
        val reason = (refusal["reason"] as? JsonPrimitive)?.content.orEmpty()
        assertAll(
            { assertEquals(JsonPrimitive("screen-refused"), refusal["type"], "the oversized screen was refused: $refusal") },
            { assertTrue("limit of $budget bytes" in reason, "the refusal states the limit: $reason") },
            { assertTrue("small" in sketchHtml(onBoard)!!, "the board still shows the screen before it") },
            { assertEquals(1, CompanionAdapter.screensShown(onBoard), "nothing of the refused screen was written") },
        )
        rig.close()
    }

    private companion object {
        const val LIMIT = 8_000
    }
}
