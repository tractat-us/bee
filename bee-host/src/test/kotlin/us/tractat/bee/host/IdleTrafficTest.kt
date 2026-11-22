@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.BEE_QUILTER_CONFIG
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val ALEX = ReplicaId("web-alex")
private val SAM = ReplicaId("mcp-sam")
private val CAROL = ReplicaId("mcp-carol")

/** How far behind its inbound frames the host has fallen, as its spokes see it. */
private val LAG = 2.seconds

/** A board-sized frame: at least half the first-contact `FullState`, which no delta here comes near. */
private const val BOARD_FRACTION = 2

/**
 * How many times the whole board may cross one spoke's link, either way, in an idle minute. At a
 * 1 s backstop it crossed 169 to 234 times; at 30 s, 2 to 5.
 */
private const val BOARD_FRAMES_PER_IDLE_MINUTE = 8

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

private suspend fun BoardClient.openBoard(surfaces: Int) = repeat(surfaces) { n ->
    val id = "s$n"
    val meta = SurfaceMeta(id, NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-06T12:00:00Z"))
    val components = List(6) { i ->
        buildJsonObject {
            put("id", if (i == 0) "root" else "$id-c$i")
            put("component", "Text")
            put("text", "Component $i of surface $id, with enough words to weigh what a real one does.")
        }
    }
    mutate { it.openSurface(ALEX, id, meta, components, JsonObject(emptyMap())) }
}

/**
 * An idle board costs its host traffic in proportion to what changes, not to the board's size
 * times the clock (bee#256). A host that a 9 MB board and a few MCP sessions put behind its
 * inbound frames compared each spoke's root digest, every anti-entropy round, against a board
 * that had moved on (a presence post every [Presence.EVERY] is enough), so each round asked for,
 * or shipped, the whole board. Each of those frames took longer to take in than the round
 * took to come round again, so they queued without end, in every link's spools, until the heap
 * ran out: 31 GB on a dev laptop.
 *
 * The test pins the production tuning ([BEE_QUILTER_CONFIG]) and makes the host [LAG] behind on
 * every link. The signal is a count of board-sized frames, which a working and a broken tuning
 * both produce but in very different numbers: one a round, against one a presence post.
 */
class IdleTrafficTest {
    @Test
    fun anIdleBoardBehindALaggingHostShipsTheWholeBoardRarely() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this, BEE_QUILTER_CONFIG.copy(expectVirtualTime = true), lag = LAG)
        star.start()
        val alex = star.client(ALEX)
        alex.connect()
        settle(LAG * 3)
        alex.openBoard(surfaces = 40)
        settle(10.seconds)
        val spokes = listOf(ALEX to alex) + listOf(SAM, CAROL).map { it to star.client(it) }
        spokes.drop(1).forEach { (_, client) -> client.connect() }
        settle(10.seconds)
        val board = star.taps.getValue(SAM.value).down.max()
        star.taps.values.forEach { it.clear() }

        var clock = 0L
        repeat((1.minutes / Presence.EVERY).toInt()) {
            spokes.forEach { (replica, client) ->
                val entry = PresenceEntry(PeerIdentity.human(replica.value), claimsNode = null, status = null, lastActedAt = Instant.fromEpochMilliseconds(0), waiting = false)
                client.mutate { it.postPresence(replica, entry, ++clock) }
            }
            settle(Presence.EVERY)
        }

        val boardSized = star.taps.mapValues { (_, tap) -> (tap.up + tap.down).count { it >= board / BOARD_FRACTION } }
        assertAll(
            { assertTrue(board > 50_000, "precondition: the board ($board B) outweighs a presence post") },
            { assertTrue(boardSized.values.all { it <= BOARD_FRAMES_PER_IDLE_MINUTE }, "board-sized frames ($board B) over a minute idle, per spoke: $boardSized") },
        )
    }
}
