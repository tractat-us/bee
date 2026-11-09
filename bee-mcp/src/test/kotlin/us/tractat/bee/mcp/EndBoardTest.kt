package us.tractat.bee.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [endBoard], the stdio bridge's last step on the board: a write made just before it reaches the
 * host (bee#116). The reported case: the bridge redials the host, writes, and its session ends
 * within half a second.
 *
 * Under virtual time a write's send is still queued when the bridge ends, so without the wait
 * both cases here lose it, the settled one too. Over real sockets the settled case's send
 * usually goes before the close, which is why the report saw only the redial.
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
        endBoard(board)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertAll(
            { assertTrue("before" in star.relay.state.value.openSurfaces(), "rig: the relay had the write before the redial") },
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
        endBoard(board)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(listOf("only"), star.relay.state.value.openSurfaces(), "the write reached the relay")
    }

    private fun meta() = SurfaceMeta("Ada's", NOTIONS_CATALOG_ID, PeerIdentity.agent("ada", kotlin.random.Random(1)), Instant.fromEpochMilliseconds(0))
}
