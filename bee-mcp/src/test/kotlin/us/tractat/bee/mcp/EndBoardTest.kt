package us.tractat.bee.mcp

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.bee.host.BoardStar
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [endSession], the stdio bridge's last steps on the board, by either way out: a write made just
 * before it reaches the host (bee#116). The reported case: the bridge redials the host, writes,
 * and its session ends within half a second.
 *
 * Under virtual time a write's send is still queued when the bridge ends, so without the wait
 * both cases here lose it, the settled one too. Over real sockets the settled case's send
 * usually goes before the close, which is why the report saw only the redial.
 *
 * Each case asserts the acknowledgement itself, and that it came well inside the bound: a plain
 * pause of [BOUND] lets the queued send through too, so the write reaching the relay alone can't
 * tell a wait for the ack from one.
 *
 * Not covered here: the shutdown hook a signal runs. It and the stdin path both run `main`'s
 * once-only `leave`, which is this function and nothing else; the hook itself has no seam.
 */
class EndBoardTest {
    private val bridge = ReplicaId("mcp-ada")

    /** A surface opened right after a redial, then the bridge ends at once: the relay has the surface. */
    @Test
    fun aWriteJustAfterARedialSurvivesTheBridgeEnding() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val board = star.client(bridge).also { it.connect() }
        board.online.first { it }
        board.mutate { it.openSurface(bridge, "before", meta(), validSurface(), JsonObject(emptyMap())) }
        star.relay.state.first { "before" in it.openSurfaces() }

        star.hubEnds.forEach { it.close() }
        board.online.first { !it }
        board.online.first { it }
        board.mutate { it.openSurface(bridge, "after", meta(), validSurface(), JsonObject(emptyMap())) }
        val (acknowledged, took) = timed { endSession(board, BOUND) {} }
        settle()

        assertAll(
            { assertTrue("before" in star.relay.state.value.openSurfaces(), "rig: the relay had the write before the redial") },
            { assertTrue(acknowledged, "the host acknowledged the write after the redial") },
            { assertTrue(took < BOUND / 2, "the ack ended the wait, well inside the bound: took $took") },
            { assertEquals(listOf("after", "before"), star.relay.state.value.openSurfaces().sorted(), "the write after the redial reached the relay") },
        )
    }

    /** The control: with no redial, a write and an ending at once leave the write on the relay too. */
    @Test
    fun aWriteOnASettledLinkSurvivesTheBridgeEnding() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val board = star.client(bridge).also { it.connect() }
        board.online.first { it }
        board.mutate { it.openSurface(bridge, "only", meta(), validSurface(), JsonObject(emptyMap())) }
        val (acknowledged, took) = timed { endSession(board, BOUND) {} }
        settle()

        assertAll(
            { assertTrue(acknowledged, "the host acknowledged the write") },
            { assertTrue(took < BOUND / 2, "the ack ended the wait, well inside the bound: took $took") },
            { assertEquals(listOf("only"), star.relay.state.value.openSurfaces(), "the write reached the relay") },
        )
    }

    /**
     * The `Depart` and the flush run at once, so an ending takes one bound, not two. The depart
     * here takes the whole bound, and so does the ack wait (it never returns, so the flush runs
     * to its timeout); run one after the other they would take two bounds. The depart starts while
     * the write is still unacknowledged, and the ending takes only the bound.
     */
    @Test
    fun theDepartAndTheFlushRunTogetherWithinOneBound() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val board = star.client(bridge).also { it.connect() }
        board.online.first { it }
        board.mutate { it.openSurface(bridge, "only", meta(), validSurface(), JsonObject(emptyMap())) }
        var relayHadItAtDepart: Boolean? = null
        val (acknowledged, took) = timed {
            endSession(board, BOUND, awaitAck = { awaitCancellation() }) { within ->
                relayHadItAtDepart = "only" in star.relay.state.value.openSurfaces()
                delay(within)
            }
        }
        settle()

        assertAll(
            { assertEquals(false, relayHadItAtDepart, "the depart began before the write was flushed") },
            { assertEquals(false, acknowledged, "rig: the ack wait never returned, so it timed out") },
            { assertEquals(BOUND, took, "the ending took one bound, not two") },
            { assertEquals(listOf("only"), star.relay.state.value.openSurfaces(), "the write reached the relay") },
        )
    }

    private suspend fun TestScope.timed(block: suspend () -> Boolean): Pair<Boolean, Duration> {
        val start = testScheduler.currentTime
        val result = block()
        return result to (testScheduler.currentTime - start).milliseconds
    }

    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    private fun meta() = SurfaceMeta("Ada's", NOTIONS_CATALOG_ID, PeerIdentity.agent("ada", kotlin.random.Random(1)), Instant.fromEpochMilliseconds(0))

    private companion object {
        val BOUND = 1.seconds
    }
}
