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
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * How many times the whole board may cross one spoke's link, either way, in an idle minute. On
 * kuilt 0.7.0-dev.1696 a 1 s backstop crossed 169 to 234 times, and 30 s crossed 2 to 5, both
 * with the Quilters on the wall clock. On 0.7.0-dev.1926 (kuilt#3617), with every Quilter on the
 * test scheduler's clock and a seeded random, each relay seed replays the same counts every run.
 * Across relay seeds 0 to 9, 1 s crossed 24 to 57 times, 5 s 2 to 5, and 30 s none.
 */
private const val BOARD_FRAMES_PER_IDLE_MINUTE = 8

/** The relay seeds the reconnect runs under: each replays exactly, and each is another schedule. */
private val SEEDS = 0..4

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

/** One idle minute's traffic: the first-contact board's size, and each spoke's tap as it saw that minute. */
private data class IdleMinute(val board: Int, val frames: Map<String, Frames>) {
    data class Frames(val up: List<Int>, val down: List<Int>)

    /** How many board-sized frames crossed each spoke's link, either way. */
    fun boardSized(): Map<String, Int> = frames.mapValues { (_, f) -> (f.up + f.down).count { it >= board / BOARD_FRACTION } }
}

/**
 * Three spokes on a 40-surface board, the host [LAG] behind on every link, then a minute of
 * nothing but each spoke's presence post every [Presence.EVERY], under [config].
 */
private suspend fun TestScope.idleMinute(config: QuilterConfig): IdleMinute {
    val star = BoardStar(this, config.copy(expectVirtualTime = true), lag = LAG)
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
            client.mutate { it.postPresence(replica, entry(replica, at = 0), ++clock) }
        }
        settle(Presence.EVERY)
    }
    return IdleMinute(board, star.taps.mapValues { (_, tap) -> IdleMinute.Frames(tap.up.toList(), tap.down.toList()) })
}

/**
 * A reconnect's traffic: the first-contact board's size, what came down to each spoke that stayed,
 * and when each of those spokes last saw Sam act, or null if it holds no entry of Sam's.
 */
private data class Reconnect(val board: Int, val down: Map<String, List<Int>>, val samActedAt: Map<String, Instant?>) {
    /** How many board-sized frames came down to each spoke that stayed online. */
    fun boardSized(): Map<String, Int> = down.mapValues { (_, sizes) -> sizes.count { it >= board / BOARD_FRACTION } }
}

private fun entry(replica: ReplicaId, at: Long) =
    PresenceEntry(PeerIdentity.human(replica.value), claimsNode = null, status = null, lastActedAt = Instant.fromEpochMilliseconds(at), waiting = false)

/** When Sam acts offline, in [reconnect]. */
private val SAM_OFFLINE_ACT = Instant.fromEpochMilliseconds(1)

/**
 * Three spokes on a 40-surface board under relay [seed]. Sam goes offline, posts presence there,
 * and comes back: its first-contact `FullState` is the whole board, one presence post ahead of the
 * host's. The window is the few seconds after, well inside an anti-entropy round, so what comes
 * down to Alex and Carol is the host's forward of that `FullState` and nothing else board-sized.
 */
private suspend fun TestScope.reconnect(seed: Int): Reconnect {
    val star = BoardStar(this, BEE_QUILTER_CONFIG.copy(expectVirtualTime = true), seed = seed)
    star.start()
    val alex = star.client(ALEX)
    alex.connect()
    settle(1.seconds)
    alex.openBoard(surfaces = 40)
    settle(5.seconds)
    val sam = star.client(SAM)
    val carol = star.client(CAROL)
    listOf(sam, carol).forEach { it.connect() }
    settle(5.seconds)
    val board = star.taps.getValue(SAM.value).down.max()

    sam.disconnect()
    settle(5.seconds)
    sam.mutate { it.postPresence(SAM, entry(SAM, at = SAM_OFFLINE_ACT.toEpochMilliseconds()), 1) }
    star.taps.values.forEach { it.clear() }
    sam.connect()
    settle(5.seconds)
    val stayed = mapOf(ALEX to alex, CAROL to carol)
    return Reconnect(
        board,
        stayed.keys.associate { it.value to star.taps.getValue(it.value).down.toList() },
        stayed.entries.associate { (replica, client) ->
            replica.value to client.state.value.latestPresence().singleOrNull { it.identity == PeerIdentity.human(SAM.value) }?.lastActedAt
        },
    )
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
        val minute = idleMinute(BEE_QUILTER_CONFIG)
        val boardSized = minute.boardSized()
        assertAll(
            { assertTrue(minute.board > 50_000, "precondition: the board (${minute.board} B) outweighs a presence post") },
            { assertTrue(boardSized.values.all { it <= BOARD_FRAMES_PER_IDLE_MINUTE }, "board-sized frames (${minute.board} B) over a minute idle, per spoke: $boardSized") },
        )
    }

    /**
     * The counts above are a measurement only if the rig replays exactly: every Quilter on the
     * test scheduler's clock and a seeded random. Before they were injected, each Quilter read the
     * wall clock for its stale-digest skip and drew its anti-entropy peer from an unseeded random,
     * so the same minute gave different counts run to run (tractat-us/kuilt#3622). Two runs here
     * must put the same frames, of the same sizes in the same order, on every spoke's link.
     *
     * This pins the random: with the Quilters' draws unseeded it reds every run. It can't pin the
     * clock. A whole run takes well under a second of wall time, so a wall clock makes every write
     * look recent to the skip, every run alike, and the counts come out wrong but repeatable. The
     * clock is pinned by `BoardClient` and `BoardRelay` taking it with no default.
     */
    @Test
    fun anIdleMinuteReplaysFrameForFrame() {
        val config = BEE_QUILTER_CONFIG.copy(antiEntropyInterval = 1.seconds)
        val runs = List(2) {
            lateinit var minute: IdleMinute
            runTest(StandardTestDispatcher()) { minute = idleMinute(config) }
            minute
        }
        assertAll(
            { assertTrue(runs[0].frames.values.all { it.up.isNotEmpty() && it.down.isNotEmpty() }, "precondition: every spoke's link carries frames both ways: ${runs[0].frames}") },
            { assertEquals(runs[0], runs[1], "two runs of one idle minute, board-sized frames per spoke: ${runs.map { it.boardSized() }}") },
        )
    }

    /**
     * bee#262: the host forwards what a spoke's `FullState` adds to its board, not the spoke's
     * whole state. A spoke back from offline one presence post ahead used to send a board-sized
     * frame, through the forward, to every other spoke; now the forward carries the presence.
     *
     * The count alone passes a forward that sends nothing, since digests and hellos keep `down`
     * non-empty. So each spoke that stayed must also hold Sam's offline post. No client here posts
     * presence on its own (only `Presence` does, and the rig runs none), and the window closes
     * well inside an anti-entropy round, so only the forward can have brought it.
     */
    @Test
    fun aReconnectOnePresencePostAheadForwardsNoWholeBoard() {
        val runs = SEEDS.associateWith { seed ->
            lateinit var run: Reconnect
            runTest(StandardTestDispatcher()) { run = reconnect(seed) }
            run
        }
        assertAll(
            { assertTrue(runs.values.all { it.board > 50_000 }, "precondition: the board outweighs a presence post: ${runs.mapValues { it.value.board }}") },
            { assertTrue(runs.values.all { it.down.values.all(List<Int>::isNotEmpty) }, "precondition: the reconnect reached the spokes that stayed: ${runs.mapValues { it.value.down }}") },
            { assertTrue(runs.values.all { run -> run.boardSized().values.all { it == 0 } }, "board-sized frames to the spokes that stayed, per seed: ${runs.mapValues { it.value.boardSized() }}") },
            {
                val held = SEEDS.associateWith { mapOf(ALEX.value to SAM_OFFLINE_ACT, CAROL.value to SAM_OFFLINE_ACT) }
                assertEquals(held, runs.mapValues { it.value.samActedAt }, "when the spokes that stayed saw Sam act, per seed")
            },
        )
    }
}
