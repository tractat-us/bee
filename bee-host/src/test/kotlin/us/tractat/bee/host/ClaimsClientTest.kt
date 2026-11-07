package us.tractat.bee.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClaimsClientTest {
    private val target = ClaimTarget("record:web-1:1")
    private val alice = PeerIdentity.agent("alice", Random(1))

    /**
     * Fit check (b) and (c), spike 4 Step 1: the voter proposes its own `Depart`, and a learner
     * admitted later folds that entry. The voter publishes no snapshot, so raft has no `Install`
     * to send: the learner got the entry itself, from index 1.
     */
    @Test
    fun aLearnerFoldsTheLogFromIndexOneIncludingTheVotersDepart() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val gone = PeerIdentity.agent("gone", Random(3))
        voter.depart(gone, DepartReason.Crashed)

        val a = rig.client(NodeId("claims-a"))
        val outcome = a.claim(target, alice)
        val folded = a.table.first { it.appliedIndex >= voter.table.value.appliedIndex }
        rig.report("single learner: claim latency ${a.latencies.value}, log through ${voter.table.value.appliedIndex}")
        assertAll(
            { assertIs<ClaimOutcome.Won>(outcome) },
            { assertTrue(voter.table.value.retired(gone), "the voter's own Depart committed") },
            { assertTrue(folded.retired(gone), "the learner folded an entry committed before it joined") },
            { assertEquals(voter.table.value, folded, "the learner's table is the voter's") },
            { assertEquals(rig.mirrored.value, voter.table.value, "the mirror has the voter's table") },
        )
        voter.close()
    }

    /**
     * Review Focus 4: the claim reaches the voter and commits, but the commit is held on its way
     * back, so the claimant times out and reports Unavailable. When the commit arrives, the client
     * releases the claim it won.
     */
    @Test
    fun lateCommitOfTimedOutClaimIsReleased() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val node = NodeId("claims-a")
        val a = rig.client(node)
        val warmUp = a.claim(ClaimTarget("record:web-1:0"), alice)

        val hold = rig.host.holdFor(node)
        hold.hold()
        val outcome = a.claim(target, alice, timeout = 1.seconds)
        val atVoter = voter.table.value.status(target)
        val parked = hold.parkedSends
        hold.releaseAll()
        val released = withTimeoutOrNull(10.seconds) { voter.table.first { it.status(target) == ClaimStatus.Free } }
        assertAll(
            { assertIs<ClaimOutcome.Won>(warmUp, "rig: the learner was admitted") },
            { assertTrue(parked > 0, "rig: the hold caught the commit on its way back") },
            { assertEquals(ClaimOutcome.Unavailable, outcome) },
            { assertIs<ClaimStatus.Held>(atVoter, "the claim committed at the voter while the claimant waited") },
            { assertNotNull(released, "the client released the claim it won late") },
        )
        voter.close()
    }

    @Test
    fun claimWithoutQuorumReturnsUnavailableNotWon() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val a = rig.client(NodeId("claims-a"))
        val outcome = a.claim(target, alice, timeout = 2.seconds)
        assertAll(
            { assertEquals(ClaimOutcome.Unavailable, outcome) },
            { assertEquals(ClaimStatus.Free, a.table.value.status(target)) },
        )
    }

    /**
     * Two tabs claim as one `human:` identity (spec §5.1). Tab B's claim is held on its way up, so
     * B times out, and tab A wins. B's timeout must never release A's win. Before claims carried
     * their own id, B took A's committed claim for its own late win and released A's token.
     */
    @Test
    fun aTabThatTimedOutNeverReleasesAnotherTabsWin() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val tabA = rig.client(NodeId("tab-a"))
        val tabB = rig.client(NodeId("tab-b"))
        val warm = listOf(tabA.claim(ClaimTarget("record:warm:a"), iain), tabB.claim(ClaimTarget("record:warm:b"), iain))

        val up = rig.host.holdUpFor(NodeId("tab-b"))
        up.hold()
        val timedOut = tabB.claim(target, iain, timeout = 1.seconds)
        val won = tabA.claim(target, iain)
        val parked = up.parkedSends
        up.releaseAll()
        delay(SETTLE)
        val after = voter.table.value.status(target)
        assertAll(
            { assertEquals(List(2) { true }, warm.map { it is ClaimOutcome.Won }, "rig: both tabs were admitted") },
            { assertTrue(parked > 0, "rig: the hold caught tab B's claim on its way up") },
            { assertEquals(ClaimOutcome.Unavailable, timedOut) },
            { assertIs<ClaimOutcome.Won>(won, "tab A's claim") },
            { assertEquals(ClaimStatus.Held(iain, (won as ClaimOutcome.Won).token), after, "tab A still holds its win") },
        )
        voter.close()
    }

    /**
     * A client that already won a target claims it again, and that claim times out. The timeout
     * must leave the earlier win alone. Before claims carried their own id, the timeout path
     * released any hold by the claimant, the earlier win included.
     */
    @Test
    fun aTimeoutNeverReleasesAnEarlierWin() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val node = NodeId("tab-a")
        val a = rig.client(node)
        val first = a.claim(target, iain)

        val down = rig.host.holdFor(node)
        down.hold()
        val again = a.claim(target, iain, timeout = 1.seconds)
        down.releaseAll()
        delay(SETTLE)
        val after = voter.table.value.status(target)
        assertAll(
            { assertIs<ClaimOutcome.Won>(first, "the earlier claim") },
            { assertEquals(ClaimOutcome.Unavailable, again) },
            { assertEquals(ClaimStatus.Held(iain, (first as ClaimOutcome.Won).token), after, "the earlier win still holds") },
        )
        voter.close()
    }

    /**
     * A claim times out on its way up, and the caller retries. The first claim then commits first
     * and wins. The target must end held only under a token the caller was given. Before claims
     * carried their own id, the retry forgot the first claim, so it stayed held under a token
     * nobody learned while the retry reported `Lost`.
     */
    @Test
    fun aClaimThatWinsAfterItsRetryStartedIsReleased() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val node = NodeId("tab-a")
        val a = rig.client(node)
        val warm = a.claim(ClaimTarget("record:warm:a"), iain)

        val up = rig.host.holdUpFor(node)
        up.hold()
        val first = a.claim(target, iain, timeout = 1.seconds)
        val parked = up.parkedSends
        up.releaseAll()
        val retry = a.claim(target, iain)
        delay(SETTLE)
        val after = voter.table.value.status(target)
        val given = (retry as? ClaimOutcome.Won)?.let { ClaimStatus.Held(iain, it.token) }
        assertAll(
            { assertIs<ClaimOutcome.Won>(warm, "rig: the client was admitted") },
            { assertTrue(parked > 0, "rig: the hold caught the first claim on its way up") },
            { assertEquals(ClaimOutcome.Unavailable, first) },
            { assertTrue(after !is ClaimStatus.Held || after == given, "held only under a token the caller has: retry $retry, target $after") },
        )
        voter.close()
    }

    private companion object {
        val iain = PeerIdentity.human("iain")

        /** Long enough in virtual time for parked sends to commit and for any release they set off. */
        val SETTLE = 10.seconds
    }
}
