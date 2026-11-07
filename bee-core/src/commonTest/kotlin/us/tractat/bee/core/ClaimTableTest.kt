package us.tractat.bee.core

import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaimTableTest {
    private val target = ClaimTarget("record:web-1:7")
    private val other = ClaimTarget("card:s1/c2")
    private val alice = PeerIdentity.agent("alice", Random(1))
    private val bob = PeerIdentity.agent("bob", Random(2))
    private val iain = PeerIdentity.human("iain")

    /** Folds [commands] at indices 1.. in term 1. */
    private fun table(vararg commands: ClaimCommand): ClaimTable =
        commands.foldIndexed(ClaimTable.EMPTY) { i, t, c -> t.fold(entry(i + 1L, c)) }

    private fun entry(index: Long, command: ClaimCommand, term: Long = 1L) =
        Committed.Entry(LogEntry(index = index, term = term, command = ClaimCommand.encode(command)))

    @Test
    fun firstCommittedClaimWinsAndLaterOnesAreRejected() {
        val t = table(ClaimCommand.Claim(target, alice), ClaimCommand.Claim(target, bob), ClaimCommand.Claim(target, alice))
        assertAll(
            { assertEquals(ClaimStatus.Held(alice, ClaimToken(1, 1)), t.status(target)) },
            { assertEquals(setOf(target), t.heldBy(alice)) },
            { assertEquals(emptySet(), t.heldBy(bob)) },
            { assertEquals(3L, t.appliedIndex) },
        )
    }

    @Test
    fun replayedEntryChangesNothing() {
        val once = table(ClaimCommand.Claim(target, alice))
        val release = entry(2, ClaimCommand.Release(target, alice, ClaimToken(1, 1)))
        val released = once.fold(release)
        assertAll(
            { assertEquals(once, once.fold(entry(1, ClaimCommand.Claim(target, alice)))) },
            { assertEquals(once, once.fold(entry(1, ClaimCommand.Claim(target, bob)))) },
            { assertEquals(released, released.fold(release)) },
            { assertEquals(ClaimStatus.Free, released.status(target)) },
        )
    }

    @Test
    fun internalAdvancesTheIndexOnly() {
        val t = table(ClaimCommand.Claim(target, alice))
        val advanced = t.fold(Committed.Internal(5))
        assertAll(
            { assertEquals(5L, advanced.appliedIndex) },
            { assertEquals(t.status(target), advanced.status(target)) },
            { assertEquals(advanced, advanced.fold(Committed.Internal(3))) },
        )
    }

    @Test
    fun installReplacesTheTable() {
        val snapshot = table(ClaimCommand.Claim(other, bob), ClaimCommand.Depart(bob, DepartReason.Quiet))
        val mine = table(ClaimCommand.Claim(target, alice))
        val installed = mine.fold(Committed.Install(Snapshot(throughIndex = 2, state = snapshot.encode())))
        assertAll(
            { assertEquals(snapshot, installed) },
            { assertEquals(ClaimStatus.Free, installed.status(target)) },
            { assertEquals(ClaimStatus.Orphaned(bob, ClaimToken(1, 1)), installed.status(other)) },
            { assertTrue(installed.retired(bob)) },
        )
    }

    @Test
    fun departOrphansAndReassignsNothing() {
        val t = table(
            ClaimCommand.Claim(target, alice),
            ClaimCommand.Claim(other, bob),
            ClaimCommand.Depart(alice, DepartReason.Crashed),
        )
        assertAll(
            { assertEquals(ClaimStatus.Orphaned(alice, ClaimToken(1, 1)), t.status(target)) },
            { assertEquals(ClaimStatus.Held(bob, ClaimToken(1, 2)), t.status(other)) },
            { assertEquals(setOf(target), t.heldBy(alice)) },
            { assertEquals(DepartReason.Crashed, t.departReason(alice)) },
        )
    }

    @Test
    fun staleTokenCompleteIsRejected() {
        val t = table(
            ClaimCommand.Claim(target, alice),
            ClaimCommand.Depart(alice, DepartReason.Quiet),
            ClaimCommand.Reassign(target, to = bob, by = iain),
            ClaimCommand.Complete(target, bob, ClaimToken(1, 1)),
        )
        val fresh = t.fold(entry(5, ClaimCommand.Complete(target, bob, ClaimToken(1, 3))))
        assertAll(
            { assertEquals(ClaimStatus.Held(bob, ClaimToken(1, 3)), t.status(target)) },
            { assertEquals(ClaimStatus.Done(bob), fresh.status(target)) },
        )
    }

    @Test
    fun commandFromARetiredIdentityIsRejected() {
        val retired = table(ClaimCommand.Claim(target, alice), ClaimCommand.Depart(alice, DepartReason.Cleared))
        val after = listOf(
            ClaimCommand.Claim(other, alice),
            ClaimCommand.Release(target, alice, ClaimToken(1, 1)),
            ClaimCommand.Complete(target, alice, ClaimToken(1, 1)),
            ClaimCommand.Reassign(target, to = bob, by = alice),
            ClaimCommand.Reassign(target, to = alice, by = bob),
        ).foldIndexed(retired) { i, t, c -> t.fold(entry(3L + i, c)) }
        assertAll(
            { assertEquals(ClaimStatus.Free, after.status(other)) },
            { assertEquals(ClaimStatus.Orphaned(alice, ClaimToken(1, 1)), after.status(target)) },
            { assertEquals(7L, after.appliedIndex) },
        )
    }

    @Test
    fun reassignMintsANewToken() {
        val held = table(ClaimCommand.Claim(target, alice), ClaimCommand.Reassign(target, to = bob, by = iain))
        val t = table(
            ClaimCommand.Claim(target, alice),
            ClaimCommand.Depart(alice, DepartReason.Crashed),
            ClaimCommand.Reassign(target, to = bob, by = iain),
        ).fold(entry(4, ClaimCommand.Reassign(target, to = iain, by = iain), term = 2))
        assertAll(
            { assertEquals(ClaimStatus.Held(alice, ClaimToken(1, 1)), held.status(target), "a held claim isn't reassigned") },
            { assertEquals(ClaimStatus.Held(bob, ClaimToken(1, 3)), t.status(target), "one reassign, then it's held") },
        )
    }

    @Test
    fun onlyOrphansAreReleasedWithoutAToken() {
        val held = table(ClaimCommand.Claim(target, alice), ClaimCommand.Release(target, alice, null))
        val orphaned = table(
            ClaimCommand.Claim(target, alice),
            ClaimCommand.Depart(alice, DepartReason.Crashed),
            ClaimCommand.Release(target, iain, null),
        )
        val wrongHolder = table(ClaimCommand.Claim(target, alice), ClaimCommand.Release(target, bob, ClaimToken(1, 1)))
        assertAll(
            { assertEquals(ClaimStatus.Held(alice, ClaimToken(1, 1)), held.status(target)) },
            { assertEquals(ClaimStatus.Free, orphaned.status(target)) },
            { assertEquals(ClaimStatus.Held(alice, ClaimToken(1, 1)), wrongHolder.status(target)) },
        )
    }

    @Test
    fun commandsRoundTripTheirWireForm() {
        val commands = listOf(
            ClaimCommand.Claim(target, alice),
            ClaimCommand.Release(target, alice, null),
            ClaimCommand.Release(target, alice, ClaimToken(2, 9)),
            ClaimCommand.Reassign(target, to = iain, by = bob),
            ClaimCommand.Complete(other, bob, ClaimToken(1, 4)),
            ClaimCommand.Depart(alice, DepartReason.Quiet),
        )
        assertEquals(commands, commands.map { ClaimCommand.decode(ClaimCommand.encode(it)) })
    }
}
