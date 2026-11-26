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
     * A restarted companion whose board arrives late never opens the surface a second time: it
     * waits for the board to sync and its surface to show, and writes the next screen onto it. A
     * second open would race the first, leaving two concurrent writes of the Sketch for the
     * projection to pick between, so the board could show the older screen.
     *
     * The rig loses every large hub frame to the new companion until it's released, so its
     * replica is online and empty for a while.
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
        // A companion that opens a surface before its board arrives has done it by now.
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

    /**
     * A fresh companion writes nothing until its board has synced with the host, however long
     * past the 2 s it used to guess at that takes: its first screen lands on the board the host
     * holds, not the empty one its replica started with.
     *
     * Another companion's large screen makes the board's FullState a large frame, which the rig
     * holds back from the fresh one, so its replica is online and empty until it's released.
     */
    @Test
    fun aFreshCompanionWritesNothingUntilItsBoardHasSynced() = runTest(timeout = 120.seconds) {
        val rig = rig(this).apply { start() }
        val big = rig.dirs("big")
        rig.screen("big.html", "<p>big</p>" + "<!-- ${"x".repeat(3 * CompanionRig.LATE_FRAME_BYTES)} -->", big)
        val other = rig.companion(big)
        val browser = rig.browser()
        rig.shows(browser) { "big" in it }

        rig.screen("layout.html", "<p>layout</p>")
        rig.holdBoardBack("companion-2")
        val fresh = rig.companion()
        withTimeoutOrNull(10.seconds) { while (fresh.reports.isEmpty()) delay(100) }
        val reportsHeld = fresh.reports.toList()
        val boardHeld = browser.client.state.value
        rig.releaseBoard("companion-2")

        val state = rig.shows(browser, fresh.surface) { "layout" in it }
        assertAll(
            { assertEquals(emptyList(), reportsHeld, "no screen shown before the board arrived") },
            { assertEquals(null, boardHeld.surfaceDoc(fresh.surface), "nor any write of its surface") },
            { assertEquals(setOf(other.surface, fresh.surface), state.openSurfaces().toSet(), "synced, it opens its own") },
        )
        rig.close()
    }

    /**
     * A companion whose board never syncs fails at its bound, saying so, and writes nothing: a
     * write decided against a board that hasn't arrived could open a second copy of a surface.
     */
    @Test
    fun aBoardThatNeverSyncsFailsTheCompanionAtItsBound() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        val big = rig.dirs("big")
        rig.screen("big.html", "<p>big</p>" + "<!-- ${"x".repeat(3 * CompanionRig.LATE_FRAME_BYTES)} -->", big)
        val other = rig.companion(big)
        val browser = rig.browser()
        rig.shows(browser) { "big" in it }

        rig.screen("layout.html", "<p>layout</p>")
        rig.holdBoardBack("companion-2")
        val fresh = rig.companion()
        val startedAt = testScheduler.currentTime
        val failure = withTimeoutOrNull(WAIT * 2) { fresh.awaitFailure() }
        val waited = (testScheduler.currentTime - startedAt).milliseconds
        assertAll(
            { assertTrue(failure is CompanionAdapter.BoardNotSynced, "it fails for the board that never arrived: $failure") },
            { assertTrue(waited >= CompanionAdapter.SYNC_WAIT, "it waited its bound first, not $waited") },
            { assertEquals(listOf(other.surface), browser.client.state.value.openSurfaces(), "it opened no surface") },
            { assertEquals(emptyList(), fresh.reports, "nor showed a screen") },
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
     * bee#274: a companion stopped gracefully closes its surface, and a day later the host removes
     * it. A restart over the same directories doesn't wait for the removed surface: it opens it
     * afresh with the next screen.
     */
    @Test
    fun aRestartAfterTheHostRemovedTheClosedSurfaceOpensItAfresh() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val first = rig.companion()
        rig.shows(rig.browser()) { "layout" in it }
        first.adapter.closeSurface()
        first.stop()
        rig.removeFromTheBoard(rig.surface)

        assertTheRestartOpensItAfresh(rig)
    }

    /**
     * The same, when someone else closed the surface while the companion ran: it notes the close,
     * so after it's killed and the host removes the surface, a restart doesn't wait for it.
     */
    @Test
    fun aRestartAfterSomeoneElsesCloseAndTheRemovalOpensItAfresh() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val first = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "layout" in it }
        browser.client.mutate { it.closeSurface(browser.author, rig.surface, rig.claims.clock()) }
        withTimeout(WAIT) { first.client.state.first { rig.surface in it.closedSurfaces() } }
        delay(1.seconds)
        first.stop()
        rig.removeFromTheBoard(rig.surface)

        assertTheRestartOpensItAfresh(rig)
    }

    /**
     * The same, when the companion was offline through both the close and the removal: back
     * online, its board no longer holds the surface at all, and it notes that, as it notes a close.
     */
    @Test
    fun aRestartAfterBeingOfflineThroughTheCloseAndTheRemovalOpensItAfresh() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val first = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "layout" in it }
        first.client.disconnect()
        withTimeout(WAIT) { first.client.online.first { !it } }
        browser.client.mutate { it.closeSurface(browser.author, rig.surface, rig.claims.clock()) }
        withTimeout(WAIT) { rig.star.relay.state.first { rig.surface in it.closedSurfaces() } }
        rig.removeFromTheBoard(rig.surface)
        first.client.connect()
        withTimeout(WAIT) { first.client.state.first { it.surfaceDoc(rig.surface) == null } }
        delay(1.seconds)
        first.stop()

        assertTheRestartOpensItAfresh(rig)
    }

    /**
     * bee#274, Iain's ruling: a removed id stays removed. A companion killed while its surface was
     * open, which someone then closed and the host removed, restarts believing the board has it. It
     * finds the removal, not a surface still on its way, so it neither fails with
     * [CompanionAdapter.SurfaceMissing] nor writes the removed id: it shows its next screen on a
     * fresh one.
     */
    @Test
    fun aRestartAfterBeingKilledOpenThenClosedAndRemovedOpensANewId() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val first = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "layout" in it }
        val old = rig.surface
        first.stop()
        browser.client.mutate { it.closeSurface(browser.author, old, rig.claims.clock()) }
        withTimeout(WAIT) { rig.star.relay.state.first { old in it.closedSurfaces() } }
        rig.removeFromTheBoard(old)

        val second = assertTheRestartOpensItAfresh(rig)
        assertAll(
            { assertTrue(second != old, "a new id, not $old") },
            { assertTrue(rig.star.relay.state.value.isRemoved(old), "the old id stays removed") },
        )
    }

    /**
     * The same while the companion runs: its surface closes on a waiting screen and the host
     * removes it. The agent's next screen opens a fresh id rather than fail on the removed one.
     */
    @Test
    fun aRunningCompanionWhoseSurfaceWasRemovedShowsItsNextScreenOnANewId() = runTest(timeout = 240.seconds) {
        val rig = rig(this).apply { start() }
        rig.screen("layout.html", "<p>layout</p>")
        val companion = rig.companion()
        val browser = rig.browser()
        rig.shows(browser) { "layout" in it }
        val old = rig.surface
        rig.screen("waiting.html", "<p>waiting</p>")
        withTimeout(WAIT) { rig.star.relay.state.first { old in it.closedSurfaces() } }
        rig.removeFromTheBoard(old)
        withTimeout(WAIT) { companion.client.state.first { it.isRemoved(old) } }

        rig.screen("next.html", "<p>next</p>")
        val shown = withTimeoutOrNull(WAIT) {
            while (!companion.failed.isCompleted && "next" !in sketchHtml(browser.client.state.value, companion.surface).orEmpty()) delay(100)
        }
        val failure = if (companion.failed.isCompleted) companion.awaitFailure() else null
        assertAll(
            { assertEquals(null, failure?.let { "${it::class.simpleName}: ${it.message}" }, "the companion didn't fail") },
            { assertTrue(shown != null, "the next screen showed") },
            { assertTrue(companion.surface != old, "on a new id, not $old") },
            { assertEquals(listOf(companion.surface), browser.client.state.value.openSurfaces()) },
        )
        rig.close()
    }

    /**
     * Restarts the companion over [rig]'s directories and pushes a new screen, which must show on
     * the surface opened afresh. The wait is bounded past the companion's own wait for its surface,
     * so a companion still waiting for the removed one fails first, and the red names its failure.
     * Returns the surface the restart opened.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun assertTheRestartOpensItAfresh(rig: CompanionRig): String {
        val second = rig.companion()
        rig.screen("next.html", "<p>next</p>")
        val browser = rig.browser("web-sam")
        val shown = withTimeoutOrNull(WAIT + 10.seconds) {
            while (!second.failed.isCompleted && "next" !in sketchHtml(browser.client.state.value, second.surface).orEmpty()) delay(100)
        }
        val failure = if (second.failed.isCompleted) second.awaitFailure() else null
        assertAll(
            { assertEquals(null, failure?.let { "${it::class.simpleName}: ${it.message}" }, "the restart didn't fail") },
            { assertTrue(shown != null && failure == null, "the next screen showed") },
            { assertEquals(listOf(second.surface), browser.client.state.value.openSurfaces()) },
        )
        rig.close()
        return second.surface
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
