package us.tractat.bee.core

import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.OPENED_AT
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** bee#274: the host removes a surface closed for its grace period, causally. */
class CompactClosedTest {
    private val host = ReplicaId("host-1")
    private val closedAt: Instant = OPENED_AT + 1.hours
    private val agent = PeerIdentity.agent("alice", Random(1))

    /** [SURFACE] with one record, whose claim [agent] took and the host mirrored, closed by [B]. */
    private fun closed(): BoardState {
        val voted = opened().let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val target = voted.records(SURFACE).single().first.claimTarget
        val table = ClaimTable.EMPTY.fold(
            Committed.Entry(LogEntry(index = 1, term = 1, command = ClaimCommand.encode(ClaimCommand.Claim(target, agent, ProposalId("n", 0, 1))))),
        )
        return voted.piece { it.mirrorClaims(host, table) }.piece { it.closeSurface(B, SURFACE, closedAt) }
    }

    private fun BoardState.compacted(closedBy: Instant = closedAt): BoardState = piece { it.compactClosed(host, closedBy) }

    private fun BoardState.claimTargets(): Set<String> = claims.entries.keys

    @Test
    fun aSurfaceClosedByTheCutoffGoesWithItsRecordsAndTheirClaims() {
        val before = closed()
        val after = before.compacted()
        assertAll(
            { assertEquals(1, before.records(SURFACE).size, "the control holds a record") },
            { assertEquals(1, before.claimTargets().size, "the control holds a claim") },
            { assertEquals(emptySet(), after.surfaces.keys) },
            { assertEquals(emptyList(), after.closedSurfaces()) },
            { assertEquals(emptyList(), after.records(SURFACE)) },
            { assertEquals(emptySet(), after.claimTargets()) },
        )
    }

    @Test
    fun anOpenSurfaceOrOneClosedAfterTheCutoffStays() {
        val board = closed()
        val alsoOpen = board.piece { it.openSurface(A, "open", SurfaceMeta("Open", "c", PeerIdentity.human("iain"), OPENED_AT), listOf(BoardFixtures.root), kotlinx.serialization.json.JsonObject(emptyMap())) }
        val after = alsoOpen.compacted(closedBy = closedAt - 1.hours)
        assertAll(
            { assertEquals(listOf("open"), after.openSurfaces()) },
            { assertEquals(listOf(SURFACE), after.closedSurfaces()) },
            { assertEquals(1, after.records(SURFACE).size) },
            { assertEquals(alsoOpen, after) },
        )
    }

    @Test
    fun aLaggingReplicasOlderCopyCannotBringItBack() {
        val stillOpen = opened().let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val lagging = closed()
        val compacted = lagging.compacted()
        assertAll(
            { assertEquals(emptySet(), compacted.piece(lagging).surfaces.keys) },
            { assertEquals(emptySet(), lagging.piece(compacted).surfaces.keys) },
            { assertEquals(emptySet(), stillOpen.piece(compacted).surfaces.keys) },
            { assertEquals(emptyList(), lagging.piece(compacted).records(SURFACE)) },
            { assertEquals(compacted.piece(lagging), lagging.piece(compacted)) },
        )
    }

    @Test
    fun aMirrorAfterTheRemovalDoesNotBringItsClaimBack() {
        val board = closed()
        val target = board.records(SURFACE).single().first.claimTarget
        val later = ClaimTable.EMPTY
            .fold(Committed.Entry(LogEntry(1, 1, ClaimCommand.encode(ClaimCommand.Claim(target, agent, ProposalId("n", 0, 1))))))
            .fold(Committed.Entry(LogEntry(2, 1, ClaimCommand.encode(ClaimCommand.Complete(target, agent, ClaimToken(1, 1))))))
            // Past the tombstone's own index, one past the claim's, so a mirror of it would win.
            .fold(Committed.Internal(10))
        val remirrored = board.compacted().piece { it.mirrorClaims(host, later) }
        assertEquals(emptySet(), remirrored.claimTargets())
    }

    /**
     * A write the host hadn't seen when it removed the surface keeps its tag (add-wins), so the
     * surface comes back holding that writer's own contributions. [B] never opened it, so its
     * copy has no meta, and the board doesn't show it (bee#171's guard).
     */
    @Test
    fun aRacingWriteByANonOpenerComesBackUnshown() {
        val board = closed()
        val racing = board.updateData(B, SURFACE, "/note", str("late"))
        val merged = board.compacted().piece(racing.delta)
        assertAll(
            { assertEquals(setOf(SURFACE), merged.surfaces.keys) },
            { assertEquals(null, merged.meta(SURFACE)) },
            { assertEquals(emptyList(), merged.openSurfaces()) },
            { assertEquals(emptyList(), merged.closedSurfaces()) },
        )
    }

    /**
     * The same race from the surface's opener, [A]: its copy carries its open's meta and not
     * [B]'s close, so the surface shows again, open, with [A]'s contributions alone.
     */
    @Test
    fun aRacingWriteByTheOpenerShowsItOpenWithTheOpenersContributionsAlone() {
        val board = closed().piece { it.updateData(B, SURFACE, "/byB", str("b")) }
        val racing = board.updateData(A, SURFACE, "/note", str("late"))
        val merged = board.compacted().piece(racing.delta)
        val data = merged.surfaceDoc(SURFACE)!!["data"] as us.tractat.kuilt.crdt.JsonNode.Object
        assertAll(
            { assertEquals(listOf(SURFACE), merged.openSurfaces()) },
            { assertTrue("note" in data.map.keys) },
            { assertTrue("byB" !in data.map.keys, "B's write went with the removal") },
            { assertEquals(emptyList(), merged.records(SURFACE)) },
        )
    }
}
