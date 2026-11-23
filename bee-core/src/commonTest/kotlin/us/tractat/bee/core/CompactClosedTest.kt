package us.tractat.bee.core

import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.B
import us.tractat.bee.core.BoardFixtures.OPENED_AT
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonObject
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * bee#274: the host removes a surface closed for its grace period, causally, and the id stays
 * removed for good (Iain, 2026-11-23: "276 - Yes").
 */
class CompactClosedTest {
    private val host = ReplicaId("host-1")
    private val closedAt: Instant = OPENED_AT + 1.hours
    private val removedAt: Instant = closedAt + 1.days
    private val agent = PeerIdentity.agent("alice", Random(1))
    private val again = SurfaceMeta("Lunch again", "c", PeerIdentity.human("iain"), removedAt + 2.hours)

    /** [SURFACE] with one record, whose claim [agent] took and the host mirrored, closed by [B]. */
    private fun closed(): BoardState {
        val voted = opened().let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val target = voted.records(SURFACE).single().first.claimTarget
        val table = ClaimTable.EMPTY.fold(
            Committed.Entry(LogEntry(index = 1, term = 1, command = ClaimCommand.encode(ClaimCommand.Claim(target, agent, ProposalId("n", 0, 1))))),
        )
        return voted.piece { it.mirrorClaims(host, table) }.piece { it.closeSurface(B, SURFACE, closedAt) }
    }

    private fun BoardState.compacted(closedBy: Instant = closedAt): BoardState =
        piece { it.compactClosed(host, surfaces.keys, closedBy, removedAt) }

    private fun BoardState.claimTargets(): Set<String> = claims.entries.keys

    private fun BoardState.openAgain(): BoardState = piece { it.openSurface(A, SURFACE, again, listOf(BoardFixtures.root), JsonObject(emptyMap())) }

    /** Nothing of [SURFACE] shows on this board, and it can't be opened again. */
    private fun BoardState.assertRemovedForGood() = assertAll(
        { assertTrue(isRemoved(SURFACE), "removed") },
        { assertEquals(null, surfaceDoc(SURFACE)) },
        { assertEquals(null, meta(SURFACE)) },
        { assertEquals(emptyList(), openSurfaces()) },
        { assertEquals(emptyList(), closedSurfaces()) },
        { assertEquals(emptySet(), closedBy(removedAt + 365.days)) },
        { assertEquals(emptyList(), records(SURFACE)) },
        { assertFailsWith<SurfaceRemoved> { openAgain() } },
    )

    @Test
    fun aSurfaceClosedByTheCutoffGoesWithItsRecordsAndTheirClaims() {
        val before = closed()
        val after = before.compacted()
        assertAll(
            { assertEquals(1, before.records(SURFACE).size, "the control holds a record") },
            { assertEquals(1, before.claimTargets().size, "the control holds a claim") },
            { after.assertRemovedForGood() },
            { assertEquals(emptySet(), after.claimTargets()) },
            { assertEquals(removedAt, SurfaceDoc.removedAt(after.surfaces[SURFACE]!!)) },
            { assertEquals(setOf("meta"), after.surfaces[SURFACE]!!.keys, "only the tombstone stays, not the content") },
        )
    }

    @Test
    fun anOpenSurfaceOrOneClosedAfterTheCutoffStays() {
        val board = closed()
        val alsoOpen = board.piece { it.openSurface(A, "open", SurfaceMeta("Open", "c", PeerIdentity.human("iain"), OPENED_AT), listOf(BoardFixtures.root), JsonObject(emptyMap())) }
        val after = alsoOpen.compacted(closedBy = closedAt - 1.hours)
        assertAll(
            { assertEquals(listOf("open"), after.openSurfaces()) },
            { assertEquals(listOf(SURFACE), after.closedSurfaces()) },
            { assertEquals(1, after.records(SURFACE).size) },
            { assertEquals(alsoOpen, after) },
        )
    }

    @Test
    fun aRemovedSurfaceIsNotRemovedAgain() {
        val once = closed().compacted()
        assertEquals(once, once.compacted(closedBy = removedAt + 365.days))
    }

    @Test
    fun aLaggingReplicasOlderCopyCannotBringItBack() {
        val stillOpen = opened().let { it.piece { s -> s.appendRecord(A, BoardFixtures.record(s)) } }
        val lagging = closed()
        val compacted = lagging.compacted()
        assertAll(
            { compacted.piece(lagging).assertRemovedForGood() },
            { lagging.piece(compacted).assertRemovedForGood() },
            { stillOpen.piece(compacted).assertRemovedForGood() },
            { assertEquals(setOf("meta"), lagging.piece(compacted).surfaces[SURFACE]!!.keys, "the older copy's content didn't come back") },
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

    @Test
    fun everyWriteToARemovedSurfaceIsRefused() {
        val removed = closed().compacted()
        val writes = listOf<(BoardState) -> Any>(
            { it.openAgain() },
            { it.reopenSurface(A, SURFACE) },
            { it.closeSurface(A, SURFACE, removedAt) },
            { it.retitleSurface(A, SURFACE, "Back") },
            { it.updateComponents(A, SURFACE, listOf(BoardFixtures.root)) },
            { it.updateData(A, SURFACE, "/note", str("back")) },
            { it.appendEntry(A, SURFACE, "/options", str("back")) },
            { it.appendRecord(A, BoardFixtures.record(opened())) },
        )
        val refusals = writes.map { write -> runCatching { write(removed) }.exceptionOrNull() }
        assertAll(
            { assertTrue(refusals.all { it is SurfaceRemoved }, "every write refused as removed: $refusals") },
            { assertTrue("use a new surfaceId" in refusals.first()?.message.orEmpty(), "the refusal says to use a new id") },
        )
    }

    /**
     * Found in review of #276: [B] closed the surface, then wrote to it offline, so its tag carries
     * its close. The host removes the surface. A fresh open of the same id is refused, and when
     * [B]'s write arrives it merges under the tombstone: the id stays removed and nothing of it,
     * neither [B]'s old `closedAt` nor its write, shows.
     */
    @Test
    fun theClosersLateWriteAfterTheRemovalStaysHiddenAndAFreshOpenIsRefused() {
        val board = closed()
        val racing = board.updateData(B, SURFACE, "/note", str("late"))
        val compacted = board.compacted()
        val merged = compacted.piece(racing.delta)
        assertAll(
            { assertFailsWith<SurfaceRemoved> { compacted.openAgain() } },
            { merged.assertRemovedForGood() },
            { assertTrue("data" in merged.surfaces[SURFACE]!!.keys, "the control: B's write did merge under the tombstone") },
        )
    }

    /**
     * The same write arriving before anyone tries the id again: without the tombstone, [B]'s
     * meta-less copy would sit under the id and make it silently unopenable. With it, the id says
     * it was removed, so whoever tries learns to use a new one.
     */
    @Test
    fun aLateWriteBeforeAnyReopenLeavesTheIdRemovedNotSilentlyStuck() {
        val board = closed()
        val racing = board.updateData(B, SURFACE, "/note", str("late"))
        val merged = board.compacted().piece(racing.delta)
        val refusal = runCatching { merged.openAgain() }.exceptionOrNull()
        assertAll(
            { merged.assertRemovedForGood() },
            { assertTrue(refusal is SurfaceRemoved, "refused as removed, not as already existing: $refusal") },
        )
    }

    /**
     * The same race from the surface's opener, [A]: its copy carries its open's full meta, so
     * without the tombstone the surface would show again, open. Under the tombstone it doesn't.
     */
    @Test
    fun theOpenersLateWriteAfterTheRemovalStaysHidden() {
        val board = closed().piece { it.updateData(B, SURFACE, "/byB", str("b")) }
        val racing = board.updateData(A, SURFACE, "/note", str("late"))
        val merged = board.compacted().piece(racing.delta)
        assertAll(
            { merged.assertRemovedForGood() },
            { assertEquals("Lunch", SurfaceDoc.meta(merged.surfaces[SURFACE]!!)?.title, "the control: A's open meta did merge back") },
        )
    }

    /**
     * A peer on the release before this one reads a surface's meta with [SurfaceDoc.meta] alone. A
     * tombstone, and one with a non-opener's late write under it, holds no title, so that reader
     * takes it for a surface whose open hasn't arrived, and shows nothing (bee#171's guard).
     */
    @Test
    fun aPeerThatPredatesTheTombstoneShowsNothingOfIt() {
        val board = closed()
        val racing = board.updateData(B, SURFACE, "/note", str("late"))
        val compacted = board.compacted()
        assertAll(
            { assertEquals(null, SurfaceDoc.meta(compacted.surfaces[SURFACE]!!)) },
            { assertEquals(null, SurfaceDoc.meta(compacted.piece(racing.delta).surfaces[SURFACE]!!)) },
        )
    }

    /** The tombstone is ordinary surface content, so the board's wire form, unchanged, carries it. */
    @Test
    fun aTombstoneRoundTripsThroughTheBoardsCbor() {
        val compacted = closed().compacted()
        val decoded = Cbor.decodeFromByteArray(BoardState.serializer(), Cbor.encodeToByteArray(BoardState.serializer(), compacted))
        assertAll(
            { assertEquals(compacted, decoded) },
            { decoded.assertRemovedForGood() },
        )
    }
}
