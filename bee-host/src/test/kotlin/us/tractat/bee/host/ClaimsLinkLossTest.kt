package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [claimsLinkLoss]: a node leaving the voter's config is a lost claims link (bee#28). The first
 * tests script the membership; the last two run it against the real voter and its learner grace,
 * through [Departures], on virtual time.
 */
class ClaimsLinkLossTest {
    private val host = NodeId("bee-host")
    private val a = NodeId("mcp-a-1")
    private val b = NodeId("mcp-b-1")
    private val at = Instant.fromEpochMilliseconds(42)

    private fun config(vararg learners: NodeId) = ClusterConfig(voters = setOf(host), learners = learners.toSet())

    private fun lost(node: NodeId) = PartitionEvent.PeerLost(PeerId(node.value), at)

    /** A learner the voter drops is that node's lost claims link, stamped by the clock. */
    @Test
    fun aLearnerLeavingTheConfigIsALostLink() = runTest {
        val events = claimsLinkLoss(flowOf(config(a, b), config(a)), clock = { at }).toList()
        assertEquals(listOf(lost(b)), events)
    }

    /** Learners joining lose nothing, and the first config is a baseline, not a loss of everything before it. */
    @Test
    fun learnersJoiningLoseNothing() = runTest {
        val events = claimsLinkLoss(flowOf(config(a), config(a, b), config(a, b)), clock = { at }).toList()
        assertEquals(emptyList(), events)
    }

    /**
     * Each config is compared with the one before it, so a learner is lost once: a later config
     * that adds someone else doesn't lose it again.
     */
    @Test
    fun aLearnerIsLostOnce() = runTest {
        val c = NodeId("mcp-c-1")
        val events = claimsLinkLoss(flowOf(config(a, b), config(a), config(a, c), config(c)), clock = { at }).toList()
        assertEquals(listOf(lost(b), lost(a)), events)
    }

    /** A learner made a voter is still in the cluster: its link isn't lost. Leaving it altogether, the control, is. */
    @Test
    fun aLearnerMadeAVoterIsNotLost() = runTest {
        val promoted = ClusterConfig(voters = setOf(host, a), learners = emptySet())
        val events = claimsLinkLoss(flowOf(config(a), promoted, config()), clock = { at }).toList()
        assertEquals(listOf(lost(a)), events)
    }

    /**
     * A bridge killed while holding a claim: the voter drops its claims node after the learner
     * grace, and [Departures], fed from the voter's membership, departs it `Crashed`, so its claim
     * shows orphaned. Not before the grace.
     */
    @Test
    fun aKilledBridgeDepartsCrashedAfterTheGrace() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val ada = PeerIdentity.agent("ada", Random(1))
        val target = ClaimTarget("record:web-1:1")
        val node = NodeId("mcp-ada-1")
        val (learner, kill) = learner(rig, node)
        val won = learner.claim(target, ada)
        val entries = listOf(entry(ada, node))
        Departures(claimsLinkLoss(voter.membership, rig.clock), { entries }, voter::depart, clock = rig.clock)
            .start(backgroundScope)

        val killedAt = testScheduler.currentTime
        kill()
        val after = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) { voter.table.first { it.retired(ada) } }
        val elapsed = (testScheduler.currentTime - killedAt).milliseconds
        assertAll(
            { assertIs<ClaimOutcome.Won>(won, "rig: ada held the target") },
            { assertEquals(DepartReason.Crashed, after?.departReason(ada), "departed Crashed") },
            { assertEquals(ClaimStatus.Orphaned(ada, (won as ClaimOutcome.Won).token), after?.status(target)) },
            { assertTrue(elapsed >= ClaimsVoter.LEARNER_GRACE, "not before the grace, was $elapsed") },
        )
        voter.close()
    }

    /**
     * A bridge that rebuilt its claims link under a fresh node names the new node, so the old
     * node's removal departs nobody. Then the new link dies too, the control, and it departs.
     */
    @Test
    fun aRebuiltClaimsLinkIsNotDepartedByTheOldNode() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val ada = PeerIdentity.agent("ada", Random(1))
        val old = NodeId("mcp-ada-1")
        val fresh = NodeId("mcp-ada-2")
        val (first, killFirst) = learner(rig, old)
        first.claim(ClaimTarget("record:web-1:1"), ada)
        var entries = listOf(entry(ada, old))
        Departures(claimsLinkLoss(voter.membership, rig.clock), { entries }, voter::depart, clock = rig.clock)
            .start(backgroundScope)

        killFirst()
        val (second, killSecond) = learner(rig, fresh)
        second.claim(ClaimTarget("record:web-1:2"), ada)
        entries = listOf(entry(ada, fresh))
        val oldGone = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) { voter.membership.first { old !in it.learners } }
        val afterOld = withTimeoutOrNull(5.seconds) { voter.table.first { it.retired(ada) } }
        killSecond()
        val afterFresh = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) { voter.table.first { it.retired(ada) } }
        assertAll(
            { assertTrue(oldGone != null && fresh in oldGone.learners, "rig: the old node left and the fresh one stayed, was $oldGone") },
            { assertNull(afterOld, "the old node's removal departs nobody") },
            { assertEquals(DepartReason.Crashed, afterFresh?.departReason(ada), "control: the current node's removal does") },
        )
        voter.close()
    }

    private fun entry(identity: PeerIdentity, node: NodeId) =
        PresenceEntry(identity, node, status = null, lastActedAt = Instant.DISTANT_FUTURE, waiting = false)

    /** A learner on its own job, and the kill that ends it the way a dead process ends its link. */
    private fun TestScope.learner(rig: ClaimsRig, node: NodeId): Pair<ClaimsClient, suspend () -> Unit> {
        val job = Job(backgroundScope.coroutineContext[Job])
        val client = rig.client(node, CoroutineScope(backgroundScope.coroutineContext + job))
        return client to { job.cancelAndJoin() }
    }
}
