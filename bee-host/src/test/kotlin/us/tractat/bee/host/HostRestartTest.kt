@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.Presence
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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

    /** A host closed from a cancelled coroutine (a shutdown hook's scope) still makes its last save. */
    @Test
    fun closeFromACancelledCoroutineStillSaves() = runTest(timeout = 120.seconds) {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("us.tractat.bee.host.HostBoard") as Logger
        logger.addAppender(logged)
        try {
            val rig = Rig(this)
            val first = rig.boot()
            runCurrent()
            first.board.relay.mutate { it.openSurface(ada, "lunch", meta(), listOf(root), JsonObject(emptyMap())) }
            val closing = launch {
                try {
                    awaitCancellation()
                } finally {
                    first.close()
                }
            }
            runCurrent()
            closing.cancel()
            runCurrent()

            val board = rig.openBoard()
            val restored = board.relay.state.value.openSurfaces()
            board.close()
            assertAll(
                { assertEquals(listOf("lunch"), restored, "the last save happened") },
                { assertEquals(emptyList(), logged.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }, "no store failure logged") },
            )
        } finally {
            logger.detachAppender(logged)
        }
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
        val rig = Rig(this, store)
        val id = BoardId.mint()
        BoardId.recordIn(store, id)
        BoardId.recordIn(rig.claims.store, id)
        store.write(id.snapshotKey, "not a board".encodeToByteArray())

        val refused = assertFailsWith<IllegalStateException> { rig.openBoard() }
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

    /**
     * bee#44's scenario. alice and bo each hold a claim, the host stops, and alice dies while it is
     * down. The host boots with no presence, since presence isn't saved. bo reconnects inside the
     * boot grace, on a fresh claims link as `ClaimsConnection` rebuilds one, and posts it, then
     * re-posts as a live peer does: he keeps his claim. alice is departed `Crashed` once the grace ends, so her claim shows orphaned, and
     * the departure is logged.
     */
    @Test
    fun anAgentThatDiedWhileTheHostWasDownIsDepartedAfterTheBootGrace() = runTest(timeout = 120.seconds) {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("us.tractat.bee.host.Departures") as Logger
        logger.addAppender(logged)
        try {
            val rig = Rig(this)
            val bo = PeerIdentity.agent("bo", Random(2))
            val hers = ClaimTarget("record:lunch:1")
            val his = ClaimTarget("record:lunch:2")
            val first = rig.boot()
            // The host going down ends every claims link of the first boot.
            val firstLinks = Job(backgroundScope.coroutineContext[Job])
            val linkScope = CoroutineScope(backgroundScope.coroutineContext + firstLinks)
            val aliceWon = rig.claims.client(NodeId("claims-alice-1"), linkScope).claim(hers, alice)
            val boWon = rig.claims.client(NodeId("claims-bo-1"), linkScope).claim(his, bo)
            first.close()
            firstLinks.cancelAndJoin()

            val second = rig.boot()
            advanceTimeBy(10.seconds)
            rig.claims.client(NodeId("claims-bo-2"))
            second.board.relay.mutate { it.postPresence(ReplicaId("mcp-bo"), agentPresence(bo, "claims-bo-2"), clock = 1) }
            advanceTimeBy(Presence.EVERY)
            second.board.relay.mutate { it.postPresence(ReplicaId("mcp-bo"), agentPresence(bo, "claims-bo-2"), clock = 2) }
            advanceTimeBy(Departures.BOOT_GRACE - 11.seconds - Presence.EVERY)
            runCurrent()
            val inGrace = second.voter.table.value
            val after = withTimeoutOrNull(Departures.BOOT_GRACE) { second.voter.table.first { it.retired(alice) } }
            // A check later, so a Depart of bo proposed after alice's would have committed.
            advanceTimeBy(Departures.CHECK_EVERY)
            runCurrent()
            val settled = second.voter.table.value
            val lines = logged.list.map { it.formattedMessage }
            assertAll(
                { assertIs<ClaimOutcome.Won>(aliceWon, "rig: alice held a claim") },
                { assertIs<ClaimOutcome.Won>(boWon, "rig: bo held a claim") },
                { assertIs<ClaimStatus.Held>(inGrace.status(hers), "alice keeps her claim until the grace ends") },
                { assertEquals(DepartReason.Crashed, after?.departReason(alice), "then she is departed Crashed") },
                { assertEquals(ClaimStatus.Orphaned(alice, (aliceWon as ClaimOutcome.Won).token), after?.status(hers)) },
                { assertEquals(ClaimStatus.Held(bo, (boWon as ClaimOutcome.Won).token), settled.status(his), "bo, seen inside the grace, keeps his") },
                { assertEquals(null, settled.departReason(bo), "and isn't departed") },
                { assertTrue(lines.any { "$alice" in it && "Crashed" in it }, "the departure is logged: $lines") },
            )
            second.close()
        } finally {
            logger.detachAppender(logged)
        }
    }

    /**
     * bee#44 through the real push path. alice holds a claim and dies while the host is down. A
     * tab left open across the restart holds her last presence entry, as every peer connected
     * before the outage does, and redials after the learner grace, when the voter has already
     * dropped her old claims node. Its first-contact push puts her entry on the new board inside
     * the boot grace, but the entry never advances: she is still departed `Crashed` once the grace
     * ends. Counting a pushed-back entry as seen kept her claims held for good.
     */
    @Test
    fun aDeadAgentsEntryPushedBackByASurvivingTabDoesNotCountAsSeen() = runTest(timeout = 120.seconds) {
        val rig = Rig(this)
        val hers = ClaimTarget("record:lunch:1")
        val first = rig.boot()
        val firstLinks = Job(backgroundScope.coroutineContext[Job])
        val won = rig.claims.client(NodeId("claims-alice-1"), CoroutineScope(backgroundScope.coroutineContext + firstLinks)).claim(hers, alice)
        first.close()
        firstLinks.cancelAndJoin()
        val held = BoardState.EMPTY.let { it.piece(it.postPresence(ReplicaId("mcp-alice"), agentPresence(alice, "claims-alice-1"), clock = 1).delta) }
        val tab = rig.tab(ReplicaId("web-iain"), held)

        val second = rig.boot()
        val booted = testScheduler.currentTime
        advanceTimeBy(Departures.BOOT_GRACE - 15.seconds)
        tab.connect()
        withTimeoutOrNull(10.seconds) { second.board.relay.state.first { s -> s.latestPresence().any { it.identity == alice } } }
        val pushedAfter = (testScheduler.currentTime - booted).milliseconds
        val after = withTimeoutOrNull(Departures.BOOT_GRACE) { second.voter.table.first { it.retired(alice) } }
        assertAll(
            { assertIs<ClaimOutcome.Won>(won, "rig: alice held a claim") },
            { assertTrue(second.board.relay.state.value.latestPresence().any { it.identity == alice }, "rig: the tab pushed her entry back") },
            {
                assertTrue(
                    pushedAfter > ClaimsVoter.LEARNER_GRACE && pushedAfter < Departures.BOOT_GRACE,
                    "rig: pushed after the lost-link rule's turn and inside the boot grace, at $pushedAfter",
                )
            },
            { assertEquals(DepartReason.Crashed, after?.departReason(alice), "she is departed Crashed all the same") },
            { assertEquals(ClaimStatus.Orphaned(alice, (won as ClaimOutcome.Won).token), after?.status(hers)) },
        )
        tab.close()
        second.close()
    }

    /** The host's stores, kept across its boots, and its endpoints in memory. */
    private class Rig(
        private val test: TestScope,
        private val boardStore: DurableStore = InMemoryDurableStore(),
        private val intake: AutoCloseable = AutoCloseable {},
    ) {
        val claims = ClaimsRig(test)
        private val dispatcher = StandardTestDispatcher(test.testScheduler)

        /** Where the board of the boot that is up takes connections. */
        private var boardSource = InMemoryConnectionSource()

        /** A tab holding [held], which dials whichever boot is up. */
        fun tab(author: ReplicaId, held: BoardState): BoardClient {
            val loom = SpokeLoom(test.backgroundScope, author.value, Random(0), claims.clock) { id, _ ->
                val (hubEnd, spokeEnd) = connectionPair()
                boardSource.offer(hubEnd)
                peerMesh(id, listOf(spokeEnd), dispatcher)
            }
            return BoardClient(author, loom, Rendezvous.Existing(InMemoryTag("board")), test.backgroundScope, claims.clock, held, BoardStar.CONFIG, Random(0))
        }

        suspend fun openBoard(): HostBoard {
            val stores = HostStores.settle(boardStore, claims.store)
            return HostBoard.open(test.backgroundScope, InMemoryConnectionSource(), dispatcher, stores.board, stores.boardId, claims.clock, Random(0), BoardStar.CONFIG)
        }

        suspend fun boot(): HostCore = HostCore.start(
            scope = test.backgroundScope,
            boardSource = InMemoryConnectionSource().also { boardSource = it },
            claimsHost = claims.host,
            stores = HostStores.settle(board = boardStore, claims = claims.store),
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

        fun agentPresence(identity: PeerIdentity, claimsNode: String) = PresenceEntry(
            identity = identity,
            claimsNode = NodeId(claimsNode),
            status = null,
            lastActedAt = Instant.DISTANT_FUTURE,
            waiting = false,
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
