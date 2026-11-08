package us.tractat.bee.companion

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import us.tractat.bee.companion.CompanionRig.Companion.WAIT
import us.tractat.bee.companion.CompanionRig.Companion.sketchHtml
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SketchSurfaceTest {
    /**
     * Review Focus 5: a `Sketch` over the board link's payload budget is refused with a message
     * that states the limit, nothing reaches the board, and the board keeps working: the next
     * screen that fits is shown.
     *
     * The links publish an 8 KB frame ceiling, as a framed transport would, and the clients are
     * bound under it as bee binds them under the host's. Without the refusal, the seam would drop
     * the oversized delta without a word: the companion's replica would hold the screen and no
     * other peer would ever see it.
     */
    @Test
    fun oversizedSketchIsRefusedWithItsLimit() = runTest(timeout = 120.seconds) {
        val rig = CompanionRig(this, Files.createTempDirectory("w8-sketch-"), maxFrameBytes = LIMIT)
        rig.start()
        rig.screen("small.html", "<p>small</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "small" in it }
        val budget = companion.client.maxPayloadBytes

        rig.screen("huge.html", "<p>" + "x".repeat(4 * LIMIT) + "</p>")
        // Whatever the companion did with the screen, it reports it: shown, or refused.
        val refusal = withTimeout(WAIT) {
            while (companion.reports.none { it["file"] == JsonPrimitive("huge.html") }) delay(100)
            companion.reports.single { it["file"] == JsonPrimitive("huge.html") }
        }
        // Checked first: without the refusal, the next screen never reaches the browser either,
        // because the dropped delta leaves a gap its Quilter waits on.
        assertEquals(JsonPrimitive("screen-refused"), refusal["type"], "the oversized screen was refused: $refusal")
        val onBoard = companion.client.state.value
        rig.screen("next.html", "<p>next</p>")
        rig.shows(browser) { "next" in it }
        val reason = (refusal["reason"] as? JsonPrimitive)?.content.orEmpty()
        assertAll(
            { assertTrue("limit of $budget bytes" in reason, "the refusal states the limit: $reason") },
            { assertTrue("small" in sketchHtml(onBoard, companion.surface)!!, "none of the refused screen's html was written") },
        )
        rig.close()
    }

    /**
     * A refused screen still ends the screen before it: its events are cleared, a click made on it
     * afterwards is dropped, and the events file says the new screen was refused, so the agent
     * reads that instead of a click on a screen it moved past.
     *
     * The clicking browser goes offline before the refused screen is pushed, so its click is on
     * the screen before by construction.
     */
    @Test
    fun refusedScreenEndsTheScreenBefore() = runTest(timeout = 120.seconds) {
        val rig = CompanionRig(this, Files.createTempDirectory("w8-sketch-"), maxFrameBytes = LIMIT)
        rig.start()
        rig.screen("small.html", "<p>small</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "small" in it }
        rig.click(browser, "a", "A", 1_706_000_101)
        rig.eventLines(1)
        browser.client.disconnect()
        withTimeout(WAIT) { browser.client.online.first { !it } }

        rig.screen("huge.html", "<p>" + "x".repeat(4 * LIMIT) + "</p>")
        withTimeout(WAIT) { while (companion.reports.none { it["file"] == JsonPrimitive("huge.html") }) delay(100) }
        rig.click(browser, "stale", "Stale", 1_706_000_102)
        browser.client.connect()
        // The stale click reached the companion, which handled it one way or the other.
        withTimeout(WAIT) {
            while (companion.reports.none { it["type"] == JsonPrimitive("click-dropped") } && rig.lines().none { "stale" in it }) delay(100)
        }
        val lines = rig.lines().map { Json.parseToJsonElement(it).jsonObject }
        assertAll(
            { assertEquals(1, lines.size, "one line, the refusal: $lines") },
            { assertEquals(JsonPrimitive("screen-refused"), lines.first()["type"]) },
            { assertEquals(JsonPrimitive("huge.html"), lines.first()["file"]) },
            { assertTrue("limit of" in lines.first()["reason"].toString(), "it says why: ${lines.first()}") },
        )
        rig.close()
    }

    private companion object {
        const val LIMIT = 8_000
    }
}
