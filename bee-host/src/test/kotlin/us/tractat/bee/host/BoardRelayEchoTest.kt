@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val ALEX = ReplicaId("web-alex")
private val SAM = ReplicaId("web-sam")
private val SURFACES = List(5) { "s$it" }

/** Room for the gossip, mesh and Quilter headers a forwarded delta gains on its way down. */
private const val HEADER_SLACK_BYTES = 256

private fun surfaceMeta(id: String) = SurfaceMeta(id, NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-06T12:00:00Z"))

/** Six components per surface, as in spike 2c's fixture board. */
private fun components(id: String): List<JsonObject> = List(6) { i ->
    buildJsonObject {
        put("id", if (i == 0) "root" else "$id-c$i")
        put("component", "Text")
        put("text", "Component $i of surface $id, with enough words to weigh what a real one does.")
    }
}

private fun value(state: BoardState, surface: String, key: String): JsonElement? =
    state.surfaceDoc(surface)?.toSurfaceView(surface)?.data?.get(key)

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

private suspend fun BoardClient.openBoard() = SURFACES.forEach { id ->
    mutate { it.openSurface(ALEX, id, surfaceMeta(id), components(id), JsonObject(emptyMap())) }
}

/**
 * The relay forwards a spoke's change as a delta, not as the board (#24). Each test runs the
 * in-memory [Star] under virtual time, so a spoke's only link is the hub, as over real sockets.
 */
class BoardRelayEchoTest {
    /**
     * A one-field edit reaches the other spoke, and comes back to its author, in frames the size
     * of its delta. Before #24 the hub re-broadcast its whole merged board for every change: here
     * that was a frame the size of the board, which the precondition makes at least ten deltas.
     */
    @Test
    fun aKeystrokeComesDownAtTheSizeOfItsDeltaNotTheBoard() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        val sam = star.client(SAM)
        alex.connect()
        sam.connect()
        runCurrent()
        alex.openBoard()
        settle(2400.milliseconds)
        star.taps.values.forEach { it.clear() }

        alex.mutate { it.updateData(ALEX, "s0", "/note", JsonPrimitive("hello!")) }
        settle(200.milliseconds)

        val alexTap = star.taps.getValue(ALEX.value)
        val samTap = star.taps.getValue(SAM.value)
        val delta = alexTap.up.max()
        val board = Cbor.encodeToByteArray(BoardState.serializer(), star.relay.state.value).size
        assertAll(
            { assertTrue(board >= 10 * delta, "precondition: the board ($board B) outweighs the delta ($delta B) tenfold") },
            { assertEquals(JsonPrimitive("hello!"), value(sam.state.value, "s0", "note"), "the edit reached sam") },
            { assertEquals(alex.state.value, star.relay.state.value) },
            { assertTrue(samTap.down.max() <= delta + HEADER_SLACK_BYTES, "sam's largest frame ${samTap.down.max()} B, delta $delta B: ${samTap.down}") },
            { assertTrue(alexTap.down.max() <= delta + HEADER_SLACK_BYTES, "alex's largest frame ${alexTap.down.max()} B, delta $delta B: ${alexTap.down}") },
        )
    }

    /**
     * A spoke that joins while another is mid-stream sees each later edit within a round trip,
     * not at the next anti-entropy round. Its Quilter expects the other spoke's deltas from seq 1
     * and buffers the ones that arrive, and its Resend can't reach a spoke that isn't its
     * neighbour, so the hub's own forward is what delivers the edit. Without one (the echo simply
     * removed) every edit here waited 660 ms or more, for anti-entropy.
     */
    @Test
    fun aSpokeThatJoinedMidStreamSeesEachEditWithinARoundTrip() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        alex.connect()
        runCurrent()
        alex.openBoard()
        repeat(3) { i ->
            alex.mutate { it.updateData(ALEX, "s0", "/n", JsonPrimitive(i)) }
            settle(50.milliseconds)
        }
        val sam = star.client(SAM)
        sam.connect()
        settle(3.seconds)
        // The first frames from an origin a spoke sights mid-stream wait out gossip's reorder grace.
        repeat(2) { i ->
            alex.mutate { it.updateData(ALEX, "s0", "/warm", JsonPrimitive(i)) }
            settle(2.seconds)
        }
        val joinedAfter = sam.state.value == alex.state.value

        alex.mutate { it.updateData(ALEX, "s0", "/n", JsonPrimitive(99)) }
        settle(50.milliseconds)

        assertAll(
            { assertTrue(joinedAfter, "precondition: sam caught up after joining") },
            { assertEquals(JsonPrimitive(99), value(sam.state.value, "s0", "n"), "the edit reached sam within 50 ms") },
        )
    }

    /** A spoke that loses both copies of a delta, the flood's and the hub's, still converges. */
    @Test
    fun aSpokeThatMissesADeltaStillConverges() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        val sam = star.client(SAM)
        alex.connect()
        sam.connect()
        runCurrent()
        alex.openBoard()
        settle(2.seconds)
        val samTap = star.taps.getValue(SAM.value)

        samTap.dropDown = true
        alex.mutate { it.updateData(ALEX, "s0", "/note", JsonPrimitive("lost")) }
        settle(50.milliseconds)
        samTap.dropDown = false
        val missed = value(sam.state.value, "s0", "note") == null
        alex.mutate { it.updateData(ALEX, "s1", "/note", JsonPrimitive("next")) }
        settle(3.seconds)

        assertAll(
            { assertTrue(missed, "precondition: sam missed the delta") },
            { assertEquals(JsonPrimitive("lost"), value(sam.state.value, "s0", "note")) },
            { assertEquals(JsonPrimitive("next"), value(sam.state.value, "s1", "note")) },
            { assertEquals(alex.state.value, sam.state.value) },
            { assertEquals(alex.state.value, star.relay.state.value) },
        )
    }
}
