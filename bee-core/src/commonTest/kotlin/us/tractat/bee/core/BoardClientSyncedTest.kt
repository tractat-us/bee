@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)

/** How long each frame the hub sends takes to reach a spoke. */
private val SLOW_DOWNLINK: Duration = 2.seconds

/**
 * The board's hub in memory, under virtual time, holding [board]: a Quilter over kuilt's hosted
 * overlay, as bee-host's relay is. Every frame it sends a spoke takes [SLOW_DOWNLINK] to arrive, so
 * a fresh client is online well before the hub's first-contact `FullState` reaches it.
 */
private class SlowHub(private val test: TestScope, private val board: BoardState) {
    private val source = InMemoryConnectionSource()
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }

    suspend fun start() {
        val seam = test.backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0), clock)
        Quilter(
            replica = ReplicaId("hub"),
            seam = seam,
            initial = board,
            messageSerializer = QuiltMessage.serializer(BoardState.serializer()),
            scope = test.backgroundScope,
            config = CONFIG,
        )
    }

    /** A fresh client, its board empty, whose every connection is a spoke into this hub. */
    fun client(): BoardClient {
        val loom = SpokeLoom(test.backgroundScope, "sam", Random(1), clock) { id, _ ->
            val (hubEnd, spokeEnd) = connectionPair()
            source.offer(Slow(hubEnd))
            peerMesh(id, listOf(spokeEnd), dispatcher)
        }
        return BoardClient(B, loom, Rendezvous.Existing(InMemoryTag("board")), test.backgroundScope, clock, BoardState.EMPTY, CONFIG, Random(0))
    }

    private class Slow(private val base: Connection) : Connection by base {
        override suspend fun send(frame: ByteArray) {
            delay(SLOW_DOWNLINK)
            base.send(frame)
        }
    }
}

/**
 * [BoardClient.synced]: a fresh client's board is the empty one it started with until the relay's
 * first-contact `FullState` lands, and a reader can't tell that from an empty board (bee#240). The
 * hub's downlink is slowed, so the window is forced, not raced.
 */
class BoardClientSyncedTest {
    @Test
    fun aClientIsSyncedOnlyOnceItsBoardHoldsTheRelays() = runTest(StandardTestDispatcher()) {
        val hub = SlowHub(this, opened()).apply { start() }
        val sam = hub.client().also { it.connect() }
        // Unconfined: it reads the board the instant synced turns true, before anything else runs.
        val boardWhenSynced = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            sam.synced.first { it }
            sam.state.value
        }
        sam.online.first { it }
        val syncedOnline = sam.synced.value
        val boardOnline = sam.state.value.surfaceDoc(SURFACE)
        val board = boardWhenSynced.await()
        assertAll(
            { assertFalse(syncedOnline, "online, but the hub's FullState is still on its way: not synced") },
            { assertNull(boardOnline, "precondition: the board is still the empty one the client started with") },
            { assertTrue(SURFACE in board.openSurfaces(), "the moment it is synced, the board holds the hub's surface") },
        )
    }

    /** A relay's board with nothing on it syncs too: holding it is no change, not a wait for one. */
    @Test
    fun aClientSyncsWithAnEmptyRelayBoard() = runTest(StandardTestDispatcher()) {
        val hub = SlowHub(this, BoardState.EMPTY).apply { start() }
        val sam = hub.client().also { it.connect() }
        sam.online.first { it }
        val synced = sam.awaitSynced(SLOW_DOWNLINK * 3)
        assertTrue(synced, "the empty board arrived, and the client holds it")
    }

    /**
     * [BoardClient.awaitSynced] answers false at its bound while the hub's board is on its way, and
     * true once it has landed.
     */
    @Test
    fun awaitSyncedIsBounded() = runTest(StandardTestDispatcher()) {
        val hub = SlowHub(this, opened()).apply { start() }
        val sam = hub.client().also { it.connect() }
        sam.online.first { it }
        val early = backgroundScope.async { sam.awaitSynced(SLOW_DOWNLINK / 4) }
        advanceTimeBy(SLOW_DOWNLINK / 4 + 1.milliseconds)
        runCurrent()
        val timedOut = early.isCompleted && !early.await()
        val later = sam.awaitSynced(SLOW_DOWNLINK * 3)
        val atOnce = sam.awaitSynced(Duration.ZERO)
        assertAll(
            { assertTrue(timedOut, "short of the hub's board, the wait answers false at its bound") },
            { assertTrue(later, "a longer wait sees the board land") },
            { assertTrue(atOnce, "synced for good: no wait at all") },
        )
    }
}
