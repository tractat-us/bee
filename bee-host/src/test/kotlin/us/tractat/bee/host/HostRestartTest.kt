package us.tractat.bee.host

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The host restarted on the same stores, in memory on virtual time: its board comes back from its
 * own snapshot, before the voter re-folds a single claim, and it mints under a new author. Each
 * boot is a [HostCore], the same composition `BeeHost.start` puts behind its server, so closing
 * one is the host's SIGTERM path.
 */
class HostRestartTest {
    private val ada = ReplicaId("mcp-ada")
    private val alice = PeerIdentity.agent("alice", Random(1))

    @Test
    fun surfacesRecordsAndClaimsSurviveARestart() = runTest(timeout = 120.seconds) {
        val rig = Rig(this)
        val first = rig.boot()
        first.board.relay.mutate { it.openSurface(ada, "lunch", meta(), listOf(root), JsonObject(emptyMap())) }
        first.board.relay.mutate { it.appendRecord(ada, vote(it)) }
        first.board.relay.mutate { it.postPresence(ada, presence(), clock = 1) }
        val target = ClaimTarget("record:lunch:1")
        val won = rig.claims.client(NodeId("claims-a")).claim(target, alice)
        val saved = first.board.relay.state.first { it.claimView(target)?.status == ClaimView.HELD }
        first.close()

        // The board alone, with no voter yet: what it shows came from its own snapshot.
        val board = rig.openBoard()
        val restored = board.relay.state.value
        board.close()
        val second = rig.boot()
        val refolded = second.voter.table.first { it.status(target) is ClaimStatus.Held }
        assertAll(
            { assertIs<ClaimOutcome.Won>(won) },
            { assertEquals(listOf("lunch"), restored.openSurfaces()) },
            { assertEquals(listOf("vote"), restored.records("lunch").map { it.second.name }) },
            { assertEquals(ClaimView.HELD, restored.claimView(target)?.status, "the mirror, from the board's own snapshot") },
            { assertEquals(setOf(ada), saved.presence.entries.keys, "presence was on the board") },
            { assertTrue(restored.presence.entries.isEmpty(), "presence is not saved") },
            { assertEquals(ClaimStatus.Held(alice, (won as ClaimOutcome.Won).token), refolded.status(target)) },
        )
        second.close()
    }

    @Test
    fun restartedHostMintsUnderAFreshAuthor() = runTest(timeout = 120.seconds) {
        val rig = Rig(this)
        val first = rig.boot()
        first.board.relay.mutate { it.openSurface(first.board.relay.replica, "lunch", meta(), listOf(root), JsonObject(emptyMap())) }
        val before = first.board.relay.replica
        first.close()

        val second = rig.boot()
        val after = second.board.relay.replica
        second.board.relay.mutate { it.openSurface(after, "dinner", meta(), listOf(root), JsonObject(emptyMap())) }
        val board = second.board.relay.state.value
        assertAll(
            { assertNotEquals(before, after) },
            { assertTrue(after.value.startsWith("host-"), after.value) },
            { assertEquals(listOf("dinner", "lunch"), board.openSurfaces().sorted()) },
        )
        second.close()
    }

    /**
     * The SIGTERM path: an edit inside the save throttle's window is on disk once the host has
     * closed, and the server stopped taking writes before that last save.
     */
    @Test
    fun closeSavesAnEditInsideTheThrottleWindow() = runTest(timeout = 120.seconds) {
        val events = mutableListOf<String>()
        val rig = Rig(this, RecordingStore(events), intake = AutoCloseable { events += "intake stopped" })
        val first = rig.boot()
        runCurrent()
        first.board.relay.mutate { it.openSurface(ada, "lunch", meta(), listOf(root), JsonObject(emptyMap())) }
        events.clear()
        first.close()

        val board = rig.openBoard()
        val restored = board.relay.state.value.openSurfaces()
        board.close()
        assertAll(
            { assertEquals(listOf("lunch"), restored, "close saves what the throttle hadn't") },
            { assertEquals(listOf("intake stopped", "write"), events.take(2), "the server stops before the last save") },
        )
    }

    @Test
    fun theBoardIdOutlivesARestartAndAWipedStoreMintsANewOne() = runTest(timeout = 120.seconds) {
        val rig = Rig(this)
        val first = rig.openBoard().also { it.close() }.id
        val second = rig.openBoard().also { it.close() }.id
        val wiped = Rig(this).openBoard().also { it.close() }.id
        assertAll(
            { assertEquals(first, second) },
            { assertNotEquals(first, wiped) },
        )
    }

    /** Fail-loud: an empty board over a snapshot nobody can read would hide that it was lost. */
    @Test
    fun aSnapshotThatWontDecodeStopsTheBoot() = runTest(timeout = 120.seconds) {
        val store = InMemoryDurableStore()
        val id = BoardId.mint()
        BoardId.recordIn(store, id)
        store.write(id.snapshotKey, "not a board".encodeToByteArray())

        val refused = assertFailsWith<IllegalStateException> { Rig(this, store).openBoard() }
        assertTrue(refused.message!!.contains("board/ aside"), refused.message)
    }

    @Test
    fun aFailingStoreIsReported() = runTest(timeout = 120.seconds) {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("us.tractat.bee.host.HostBoard") as Logger
        logger.addAppender(logged)
        try {
            val store = RecordingStore(mutableListOf(), failWrites = true)
            val board = Rig(this, store).openBoard()
            runCurrent()
            board.relay.mutate { it.openSurface(ada, "lunch", meta(), listOf(root), JsonObject(emptyMap())) }
            advanceTimeBy(3.seconds)
            board.close()

            val errors = logged.list.filter { it.level == Level.ERROR }
            assertAll(
                { assertTrue(errors.size >= 2, "the failed saves, then the failed last save: ${errors.map { it.formattedMessage }}") },
                { assertTrue(errors.all { it.throwableProxy?.message == "disk full" }, errors.map { it.throwableProxy?.message }.toString()) },
                { assertTrue(errors.last().formattedMessage.contains("last save"), errors.last().formattedMessage) },
            )
        } finally {
            logger.detachAppender(logged)
        }
    }

    /** The host's stores, kept across its boots, and its endpoints in memory. */
    private class Rig(
        private val test: TestScope,
        private val boardStore: DurableStore = InMemoryDurableStore(),
        private val intake: AutoCloseable = AutoCloseable {},
    ) {
        val claims = ClaimsRig(test)
        private val dispatcher = StandardTestDispatcher(test.testScheduler)

        suspend fun openBoard(): HostBoard =
            HostBoard.open(test.backgroundScope, InMemoryConnectionSource(), dispatcher, boardStore, claims.clock, Random(0), BoardStar.CONFIG)

        suspend fun boot(): HostCore = HostCore.start(
            scope = test.backgroundScope,
            boardSource = InMemoryConnectionSource(),
            claimsHost = claims.host,
            stores = HostStores(board = boardStore, claims = claims.store),
            intake = intake,
            dispatcher = dispatcher,
            raftConfig = claims.raftConfig,
            clock = claims.clock,
            random = Random(0),
            quilterConfig = BoardStar.CONFIG,
        )
    }

    /** An in-memory store that records each write of a board snapshot in [events], or fails them all. */
    private class RecordingStore(
        private val events: MutableList<String>,
        private val failWrites: Boolean = false,
        private val delegate: DurableStore = InMemoryDurableStore(),
    ) : DurableStore by delegate {
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key == BoardId.KEY) return delegate.write(key, bytes)
            if (failWrites) throw IllegalStateException("disk full")
            events += "write"
            delegate.write(key, bytes)
        }
    }

    private companion object {
        val root: JsonObject = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "Lunch?")
        }

        fun meta() = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), Instant.parse("2026-11-07T12:00:00Z"))

        fun vote(state: BoardState) = ActionRecord(
            surfaceId = "lunch",
            componentId = "root",
            actor = PeerIdentity.human("iain"),
            name = "vote",
            context = JsonObject(emptyMap()),
            basis = Basis.capture(state, "lunch"),
            at = Instant.parse("2026-11-07T12:01:00Z"),
        )

        fun presence() = PresenceEntry(
            identity = PeerIdentity.human("iain"),
            claimsNode = NodeId("claims-iain"),
            status = null,
            lastActedAt = Instant.parse("2026-11-07T12:01:00Z"),
            waiting = false,
        )
    }
}
