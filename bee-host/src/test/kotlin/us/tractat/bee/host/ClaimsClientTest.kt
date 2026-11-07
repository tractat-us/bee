package us.tractat.bee.host

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
}
