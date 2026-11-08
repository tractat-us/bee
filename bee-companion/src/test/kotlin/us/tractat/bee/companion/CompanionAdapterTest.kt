package us.tractat.bee.companion

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.bee.companion.CompanionRig.Companion.WAIT
import us.tractat.bee.companion.CompanionRig.Companion.sketchHtml
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CompanionAdapterTest {
    private fun rig(test: kotlinx.coroutines.test.TestScope) = CompanionRig(test, Files.createTempDirectory("w8-companion-"))

    /** How many concurrent values the surface's `root` component holds: one, unless two writers raced. */
    private fun rootWrites(state: BoardState, surface: String): Int {
        val components = state.surfaceDoc(surface)!![SurfaceFormat.COMPONENTS] as JsonNode.Object
        return (components.map["root"] as JsonNode.Leaf).register.values.size
    }

    /** The newest screen by mtime, not the last written name, becomes the companion surface's one Sketch. */
    @Test
    fun newestScreenBecomesTheSketch() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("b-older.html", "<p>older</p>")
        rig.screen("a-newer.html", "<p>newer</p>")
        rig.companion()
        val browser = rig.browser()

        val state = rig.shows(browser) { "newer" in it }
        val view = state.surfaceDoc(rig.surface)!!.toSurfaceView(rig.surface)
        assertAll(
            { assertEquals(listOf(rig.surface), state.openSurfaces()) },
            { assertEquals(setOf("root"), view.components.keys, "one component") },
            { assertEquals(JsonPrimitive("Sketch"), view.components.getValue("root")["component"]) },
            { assertFalse("older" in sketchHtml(state, rig.surface)!!, "the older screen isn't shown") },
        )
        rig.close()
    }

    /** A fragment is wrapped in the frame template, so it gets its CSS; a full document is shown exactly as written. */
    @Test
    fun fragmentIsFramedAndFullDocumentIsNot() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("fragment.html", "<h2>Pick one</h2>")
        rig.companion()
        val browser = rig.browser()
        val framed = sketchHtml(rig.shows(browser) { "Pick one" in it }, rig.surface)!!

        val full = "<!DOCTYPE html>\n<html><body><h1>Whole page</h1></body></html>"
        rig.screen("full.html", full)
        val shown = sketchHtml(rig.shows(browser) { "Whole page" in it }, rig.surface)!!
        assertAll(
            { assertTrue(".frame-css" in framed, "the fragment carries the template's CSS: $framed") },
            { assertTrue("<div id=\"frame-content\"><h2>Pick one</h2></div>" in framed, "inside the template's content slot: $framed") },
            { assertFalse("<!-- CONTENT -->" in framed, "the slot is filled, not left") },
            { assertEquals(full, shown, "a full document is shown as-is") },
        )
        rig.close()
    }

    /** A click on the sketch lands in `state_dir/events` in the companion's own format, stamped when it was made. */
    @Test
    fun clickIsWrittenInTheCompanionsEventFormat() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<div data-choice=\"a\">Option A</div>")
        rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "Option A" in it }

        rig.click(browser, choice = "a", text = "Option A", atEpochSeconds = 1_706_000_101)
        assertEquals(
            listOf("""{"type":"click","choice":"a","text":"Option A","timestamp":1706000101}"""),
            rig.eventLines(1),
        )
        rig.close()
    }

    /** A new screen clears the events file, and a click made on the screen it replaced never lands in the new one's. */
    @Test
    fun newScreenClearsEvents() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("first.html", "<p>first</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "first" in it }
        rig.click(browser, "a", "A", 1_706_000_101)
        rig.eventLines(1)
        // A second browser saw only the first screen: it goes offline before the next one is
        // pushed, so its click is on the first screen by construction, and lands once it's back.
        val late = rig.browser("web-late")
        rig.shows(late) { "first" in it }
        late.client.disconnect()
        withTimeout(WAIT) { late.client.online.first { !it } }

        rig.screen("second.html", "<p>second</p>")
        rig.shows(browser) { "second" in it }
        val cleared = withTimeout(WAIT) { while (rig.events.exists()) delay(100); true }
        rig.click(late, "stale", "Stale", 1_706_000_102)
        late.client.connect()
        rig.click(browser, "b", "B", 1_706_000_103)
        // Control: the stale click reached the companion, which dropped it.
        withTimeout(WAIT) { while (companion.reports.none { it["type"] == JsonPrimitive("click-dropped") }) delay(100) }
        val lines = rig.eventLines(1)
        assertAll(
            { assertTrue(cleared, "the new screen cleared the events file") },
            { assertEquals(listOf("""{"type":"click","choice":"b","text":"B","timestamp":1706000103}"""), lines) },
        )
        rig.close()
    }

    /**
     * A restarted companion whose board arrives after the settle bound never opens the surface a
     * second time: it waits for the board's surface and writes the next screen onto it. A second
     * open would race the first, leaving two concurrent writes of the Sketch for the projection to
     * pick between, so the board could show the older screen.
     *
     * The rig loses every large hub frame to the new companion until it's released, so its
     * replica is online and empty for longer than the settle bound.
     */
    @Test
    fun restartWaitsForTheSurfaceItOpened() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        // Big enough that the board's FullState is a large frame.
        rig.screen("layout.html", "<p>layout</p>" + "<!-- ${"x".repeat(3 * CompanionRig.LATE_FRAME_BYTES)} -->")
        val first = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "layout" in it }
        first.stop()

        rig.screen("second.html", "<p>second</p>")
        rig.holdBoardBack("companion-2")
        val second = rig.companion()
        // Long past the settle bound: a companion that opens a surface has done it by now.
        withTimeoutOrNull(10.seconds) { while (second.reports.isEmpty()) delay(100) }
        rig.releaseBoard("companion-2")

        val state = rig.shows(browser) { "second" in it }
        assertAll(
            { assertEquals(1, rootWrites(state, rig.surface), "one write of the Sketch, not two concurrent ones") },
            { assertEquals(2, CompanionAdapter.screensShown(state, rig.surface), "both screens in /screens") },
            { assertEquals(listOf(rig.surface), state.openSurfaces()) },
        )
        rig.close()
    }

    /** `waiting.html` closes the companion surface; it stays in the history strip, never deleted. */
    @Test
    fun waitingScreenClosesTheSurface() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("mockup.html", "<p>mockup</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "mockup" in it }

        rig.screen("waiting-2.html", "<p>Continuing in terminal...</p>")
        val state = withTimeout(WAIT) { browser.client.state.first { rig.surface in it.closedSurfaces() } }
        // A click on the closed surface, by a browser that saw it close: no screen takes clicks now.
        rig.click(browser, "after", "After", 1_706_000_101)
        withTimeout(WAIT) {
            while (companion.reports.none { it["type"] == JsonPrimitive("click-dropped") } && rig.lines().isEmpty()) delay(100)
        }
        assertAll(
            { assertEquals(emptyList(), state.openSurfaces()) },
            { assertTrue("mockup" in sketchHtml(state, rig.surface)!!, "the closed surface keeps its last sketch") },
            { assertEquals(emptyList(), rig.lines(), "the click on the closed surface was dropped") },
        )
        rig.close()
    }

    /**
     * A companion that stops gracefully closes its surface, so a finished session leaves no open,
     * dead Sketch on the board. It's killed the moment [CompanionAdapter.closeSurface] returns, on
     * a link whose frames take [CompanionRig.SLOW_UPLINK] to leave, so the host has the close only
     * if the companion waited for it to be acknowledged.
     */
    @Test
    fun gracefulStopLeavesTheSurfaceClosed() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("mockup.html", "<p>mockup</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "mockup" in it }
        val surface = rig.surface

        rig.slowUplink(companion.name)
        val acknowledged = companion.adapter.closeSurface()
        companion.stop()
        val host = rig.star.relay.state.value
        assertAll(
            { assertTrue(acknowledged, "the host acknowledged the close") },
            { assertEquals(listOf(surface), host.closedSurfaces(), "the host has the surface closed") },
            { assertTrue("mockup" in sketchHtml(host, surface)!!, "the closed surface keeps its last sketch") },
        )
        rig.close()
    }

    /**
     * A companion killed and started again over the same directories loses nothing: the board
     * still shows the screen without a second push, events already written stay and aren't
     * written twice, and a click made while it was down lands once it's back.
     */
    @Test
    fun adapterRestartLosesNothing() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val first = rig.companion()
        val browser = rig.browser()
        val shown = rig.shows(browser) { "layout" in it }
        rig.click(browser, "a", "A", 1_706_000_101)
        rig.eventLines(1)

        first.stop()
        rig.click(browser, "b", "B", 1_706_000_102)
        val second = rig.companion()
        val lines = rig.eventLines(2)
        val after = second.client.state.value
        assertAll(
            {
                assertEquals(
                    listOf(
                        """{"type":"click","choice":"a","text":"A","timestamp":1706000101}""",
                        """{"type":"click","choice":"b","text":"B","timestamp":1706000102}""",
                    ),
                    lines,
                )
            },
            { assertEquals(sketchHtml(shown, rig.surface), sketchHtml(after, rig.surface), "the board shows the same screen") },
            { assertEquals(1, CompanionAdapter.screensShown(after, rig.surface), "the restart didn't push the screen again") },
            { assertEquals(emptyList(), second.reports.filter { it["type"] == JsonPrimitive("screen-shown") }, "nor report one") },
        )
        rig.close()
    }

    /**
     * A companion whose state says the board had its surface, restarted against the same board
     * without it (the host crashed before its save), waits a bounded time and then fails, saying
     * how to reopen. It never waits forever while still advertised, and never opens a second
     * surface.
     */
    @Test
    fun surfaceThatNeverArrivesFailsLoudAfterTheBound() = runTest(timeout = 240.seconds) {
        val dir = Files.createTempDirectory("w8-companion-")
        val board = BoardId("board-crashed")
        val before = CompanionRig(this, dir, board = board).apply { start() }
        before.screen("layout.html", "<p>layout</p>")
        val first = before.companion()
        before.shows(before.browser()) { "layout" in it }
        first.stop()
        before.close()

        val after = CompanionRig(this, dir, board = board).apply { start() }
        val browser = after.browser()
        val second = after.companion()
        val startedAt = testScheduler.currentTime
        val failure = withTimeout(WAIT * 2) { second.awaitFailure() }
        val waited = (testScheduler.currentTime - startedAt).milliseconds
        assertAll(
            { assertTrue("delete the file to reopen" in failure.message.orEmpty(), "it says how to reopen: ${failure.message}") },
            { assertTrue(waited >= 60.seconds, "it waited the bound first, not $waited") },
            { assertEquals(emptyList(), browser.client.state.value.openSurfaces(), "it opened no surface") },
        )
        after.close()
    }

    /**
     * A companion whose state names another board (a new host, a moved `board/`, another
     * `BEE_HOST`) doesn't wait for a surface that board never had: it shows the newest screen.
     */
    @Test
    fun anotherBoardTakesTheFreshOpenPath() = runTest(timeout = 240.seconds) {
        val dir = Files.createTempDirectory("w8-companion-")
        val before = CompanionRig(this, dir, board = BoardId("board-old")).apply { start() }
        before.screen("layout.html", "<p>layout</p>")
        val first = before.companion()
        before.shows(before.browser()) { "layout" in it }
        first.stop()
        before.close()

        val after = CompanionRig(this, dir, board = BoardId("board-new")).apply { start() }
        val second = after.companion()
        val state = after.shows(after.browser()) { "layout" in it }
        assertAll(
            { assertEquals(listOf(second.surface), state.openSurfaces()) },
            { assertFalse(second.failed.isCompleted, "it didn't fail") },
        )
        after.close()
    }

    /**
     * Two companions on one board, each on its own directories, keep their clicks apart: a click
     * on A's screen reaches A's events and never B's.
     */
    @Test
    fun twoCompanionsOnOneBoardKeepTheirClicksApart() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        val dirsB = rig.dirs("b")
        rig.screen("alpha.html", "<p>alpha</p>")
        rig.screen("beta.html", "<p>beta</p>", dirsB)
        val a = rig.companion()
        val b = rig.companion(dirsB)
        val browser = rig.browser()
        rig.shows(browser, a.surface) { "alpha" in it }
        rig.shows(browser, b.surface) { "beta" in it }

        rig.click(browser, "a", "A", 1_706_000_101, a.surface)
        rig.eventLines(1)
        // Control: B handles its own clicks, so its events could have held A's.
        rig.click(browser, "b", "B", 1_706_000_102, b.surface)
        rig.eventLines(1, dirsB)
        delay(5.seconds)
        assertAll(
            { assertTrue(a.surface != b.surface, "each companion has its own surface: ${a.surface}") },
            { assertEquals(listOf("""{"type":"click","choice":"a","text":"A","timestamp":1706000101}"""), rig.lines()) },
            { assertEquals(listOf("""{"type":"click","choice":"b","text":"B","timestamp":1706000102}"""), rig.lines(dirsB)) },
        )
        rig.close()
    }
}
