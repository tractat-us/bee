package us.tractat.bee.host

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A voter restarted on the same store re-folds its durable log and republishes the table. */
class VoterRestartTest {
    private val target = ClaimTarget("record:web-1:1")
    private val alice = PeerIdentity.agent("alice", Random(1))

    @Test
    fun committedClaimSurvivesARestart() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val first = rig.startVoter()
        val won = rig.client(NodeId("claims-a")).claim(target, alice)
        val through = first.table.first { it.retired(alice) }.appliedIndex
        first.close()
        rig.mirrored.value = ClaimTable.EMPTY

        val second = rig.startVoter()
        val refolded = second.table.first { it.appliedIndex >= through }
        assertAll(
            { assertIs<ClaimOutcome.Won>(won) },
            { assertEquals(ClaimStatus.Held(alice, (won as ClaimOutcome.Won).token), refolded.status(target)) },
            { assertEquals(refolded.status(target), rig.mirrored.value.status(target), "republished to the mirror") },
        )
        second.close()
    }

    @Test
    fun retiredIdentityStaysRetiredAfterRestart() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val first = rig.startVoter()
        first.depart(alice, DepartReason.Quiet)
        val through = first.table.first { it.retired(alice) }.appliedIndex
        first.close()

        val second = rig.startVoter()
        val refolded = second.table.first { it.appliedIndex >= through }
        assertAll(
            { assertTrue(refolded.retired(alice)) },
            { assertEquals(DepartReason.Quiet, refolded.departReason(alice)) },
        )
        second.close()
    }
}
