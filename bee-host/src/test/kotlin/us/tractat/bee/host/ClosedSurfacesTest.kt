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
import us.tractat.bee.core.ClaimCommand
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.ProposalId
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val SAM = ReplicaId("web-sam")
private val KEPT_FOR = 1.hours

/** The longest past its grace period a surface waits for the next check, plus a little. */
private val NEXT_CHECK = KEPT_FOR / 4 + 2.seconds

private fun TestScope.settle(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

/**
 * bee#274 over the in-memory relay, under the scheduler's clock: the host removes a surface once
 * both its close and the host's first sight of it closed are a grace period old, and the removal
 * reaches a page that held it.
 */
class ClosedSurfacesTest {
    @Test
    fun aSurfaceClosedForItsGracePeriodLeavesTheHostAndThePage() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        sam.mutate { it.openSurface(SAM, "old", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.openSurface(SAM, "kept", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "old", star.clock()) }
        settle(1.seconds)
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)

        settle(KEPT_FOR - 5.minutes)
        val inGrace = star.relay.state.value
        settle(5.minutes + NEXT_CHECK)
        assertAll(
            { assertEquals(listOf("old"), inGrace.closedSurfaces(), "inside its grace period the surface stays") },
            { assertEquals(setOf("kept"), star.relay.state.value.surfaces.keys, "the host removed it") },
            { assertEquals(setOf("kept"), sam.state.value.surfaces.keys, "the page took the removal") },
            { assertEquals(listOf("kept"), sam.state.value.openSurfaces()) },
        )
    }

    @Test
    fun aSurfaceReopenedInsideItsGracePeriodStays() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        sam.mutate { it.openSurface(SAM, "old", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "old", star.clock()) }
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)
        settle(KEPT_FOR / 2)
        sam.mutate { it.reopenSurface(SAM, "old") }
        settle(KEPT_FOR * 3)
        assertEquals(listOf("old"), sam.state.value.openSurfaces())
    }

    /**
     * A close stamped long before the host gets it (made offline, or by a slow clock) still gets
     * its whole grace period, counted from when the host first saw it closed.
     */
    @Test
    fun aCloseThatArrivesLateGetsItsWholeGracePeriod() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)
        sam.mutate { it.openSurface(SAM, "late", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "late", star.clock() - 2.days) }
        settle(KEPT_FOR - 5.minutes)
        val inGrace = star.relay.state.value.closedSurfaces()
        settle(5.minutes + NEXT_CHECK * 2)
        assertAll(
            { assertEquals(listOf("late"), inGrace, "an old closedAt alone doesn't remove it") },
            { assertEquals(emptySet(), star.relay.state.value.surfaces.keys, "a period after the host saw it, it goes") },
        )
    }

    /**
     * A boot counts as the first sight of every surface already closed, so nothing goes at boot,
     * and a page that reopened one offline while the host was down has a whole period to come back.
     */
    @Test
    fun aReopenMadeOfflineWhileTheHostWasDownSurvivesItsBoot() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val sam = star.client(SAM).apply { connect() }
        settle(1.seconds)
        sam.mutate { it.openSurface(SAM, "old", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.openSurface(SAM, "older", meta(star), listOf(root), JsonObject(emptyMap())) }
        sam.mutate { it.closeSurface(SAM, "old", star.clock() - 2.days) }
        sam.mutate { it.closeSurface(SAM, "older", star.clock() - 2.days) }
        settle(1.seconds)
        sam.disconnect()
        sam.mutate { it.reopenSurface(SAM, "old") }

        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)
        settle(KEPT_FOR / 2)
        val afterBoot = star.relay.state.value.closedSurfaces().toSet()
        sam.connect()
        settle(KEPT_FOR * 3)
        assertAll(
            { assertEquals(setOf("old", "older"), afterBoot, "nothing went at boot") },
            { assertEquals(listOf("old"), star.relay.state.value.openSurfaces(), "the offline reopen kept it") },
            { assertEquals(setOf("old"), star.relay.state.value.surfaces.keys, "the one left closed went") },
        )
    }

    /**
     * A person closed an orphan's decision while its target is still orphaned: the host keeps it,
     * closed, rather than remove it and have `OrphanDecisions` post it again. The control, another
     * surface closed at the same time, goes.
     */
    @Test
    fun aClosedDecisionStaysWhileItsTargetIsOrphaned() = runTest(StandardTestDispatcher()) {
        val star = BoardStar(this).apply { start() }
        val target = ClaimTarget("record:web-iain:1")
        val decision = OrphanDecision.surfaceId(target)
        val agent = PeerIdentity.agent("ada", Random(1))
        val orphaned = listOf(ClaimCommand.Claim(target, agent, ProposalId("n", 0, 1)), ClaimCommand.Depart(agent, DepartReason.Crashed))
            .foldIndexed(ClaimTable.EMPTY) { i, table, command -> table.fold(Committed.Entry(LogEntry(i + 1L, 1, ClaimCommand.encode(command)))) }
        star.relay.mutate { it.mirrorClaims(star.relay.replica, orphaned) }
        listOf(decision, "other").forEach { id ->
            star.relay.mutate { it.openSurface(star.relay.replica, id, meta(star), listOf(root), JsonObject(emptyMap())) }
            star.relay.mutate { it.closeSurface(star.relay.replica, id, star.clock()) }
        }
        ClosedSurfaces(star.relay, KEPT_FOR, star.clock).start(backgroundScope)
        settle(KEPT_FOR * 3)
        assertAll(
            { assertEquals(ClaimView.ORPHANED, star.relay.state.value.claimView(target)?.status, "rig: the target is orphaned") },
            { assertEquals(listOf(decision), star.relay.state.value.closedSurfaces()) },
        )
    }

    /** The host runs the removal: `HostCore.start` starts it with [HostCore]'s grace setting. */
    @Test
    fun theHostCoreRemovesASurfaceClosedForItsGracePeriod() = runTest(timeout = 120.seconds) {
        val claims = ClaimsRig(this)
        val core = HostCore.start(
            scope = backgroundScope,
            boardSource = InMemoryConnectionSource(),
            claimsHost = claims.host,
            stores = HostStores.settle(board = InMemoryDurableStore(), claims = claims.store),
            intake = AutoCloseable {},
            dispatcher = StandardTestDispatcher(testScheduler),
            raftConfig = claims.raftConfig,
            clock = claims.clock,
            timeSource = testScheduler.timeSource,
            random = Random(0),
            quilterConfig = BoardStar.CONFIG,
            closedKeptFor = KEPT_FOR,
        )
        val relay = core.board.relay
        val meta = SurfaceMeta("Notes", NOTIONS_CATALOG_ID, PeerIdentity.human("sam"), claims.clock())
        relay.mutate { it.openSurface(relay.replica, "old", meta, listOf(root), JsonObject(emptyMap())) }
        relay.mutate { it.closeSurface(relay.replica, "old", claims.clock()) }
        settle(KEPT_FOR * 2 + NEXT_CHECK * 2)
        val left = relay.state.value.surfaces.keys
        core.close()
        assertEquals(emptySet(), left)
    }

    @Test
    fun aClosedSurfaceIsKeptForADayByDefault() {
        assertAll(
            { assertEquals(1.days, ClosedSurfaces.KEPT_FOR) },
            { assertEquals(1.days, HostConfig(dataDir = kotlin.io.path.Path("unused")).closedKeptFor) },
        )
    }

    private fun meta(star: BoardStar) = SurfaceMeta("Notes", NOTIONS_CATALOG_ID, PeerIdentity.human("sam"), star.clock())

    private val root: JsonObject = buildJsonObject {
        put("id", "root")
        put("component", "Text")
        put("text", "Notes")
    }
}
