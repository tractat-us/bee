package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimsClient
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
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

    private fun config(learners: Set<NodeId>) = ClusterConfig(voters = setOf(host), learners = learners)

    private fun lost(node: NodeId) = PartitionEvent.PeerLost(PeerId(node.value), at)

    /** A learner the voter drops is that node's lost claims link, stamped by the clock. */
    @Test
    fun aLearnerLeavingTheConfigIsALostLink() = runTest {
        val events = claimsLinkLoss(flowOf(config(setOf(a, b)), config(setOf(a))), clock = { at }).toList()
        assertEquals(listOf(lost(b)), events)
    }

    /**
     * One observed config can add one learner and remove another, so the size is unchanged. Only
     * the removed learner is lost, not the added one, and not for want of a bigger set.
     */
    @Test
    fun aConfigThatAddsAndRemovesLosesOnlyTheRemoved() = runTest {
        val c = NodeId("mcp-c-1")
        val events = claimsLinkLoss(flowOf(config(setOf(a, b)), config(setOf(a, c))), clock = { at }).toList()
        assertEquals(listOf(lost(b)), events)
    }

    /** Learners joining lose nothing, and the first config is a baseline, not a loss of everything before it. */
    @Test
    fun learnersJoiningLoseNothing() = runTest {
        val events = claimsLinkLoss(flowOf(config(setOf(a)), config(setOf(a, b)), config(setOf(a, b))), clock = { at }).toList()
        assertEquals(emptyList(), events)
    }

    /**
     * Each config is compared with the one before it, so a learner is lost once: a later config
     * that adds someone else doesn't lose it again.
     */
    @Test
    fun aLearnerIsLostOnce() = runTest {
        val c = NodeId("mcp-c-1")
        val events = claimsLinkLoss(flowOf(config(setOf(a, b)), config(setOf(a)), config(setOf(a, c)), config(setOf(c))), clock = { at }).toList()
        assertEquals(listOf(lost(b), lost(a)), events)
    }

    /**
     * A learner made a voter is still in the cluster: its link isn't lost then, only when it
     * leaves altogether. The order tells the two apart: `b`'s loss, between them, comes first.
     */
    @Test
    fun aLearnerMadeAVoterIsNotLost() = runTest {
        val configs = flowOf(
            config(setOf(a, b)),
            ClusterConfig(voters = setOf(host, a), learners = setOf(b)),
            ClusterConfig(voters = setOf(host, a), learners = emptySet()),
            config(emptySet()),
        )
        val events = claimsLinkLoss(configs, clock = { at }).toList()
        assertEquals(listOf(lost(b), lost(a)), events)
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
        Departures(claimsLinkLoss(voter.membership, rig.clock), { entries }, emptyFlow(), { emptySet() }, voter::depart, clock = rig.clock)
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
        Departures(claimsLinkLoss(voter.membership, rig.clock), { entries }, emptyFlow(), { emptySet() }, voter::depart, clock = rig.clock)
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

    /**
     * The host's own wiring ([hostDepartures]) departs a killed bridge although its presence entry
     * has aged out of the live roster by then. The voter drops a node a whole learner grace after
     * its link went, and an entry leaves the live roster a [LivePresence.TTL] after its last post,
     * and the grace is no shorter. So a lost node is matched against each poster's newest entry.
     */
    @Test
    fun theHostDepartsAKilledBridgeWhoseEntryAgedOut() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val ada = PeerIdentity.agent("ada", Random(1))
        val node = NodeId("mcp-ada-1")
        val (learner, kill) = learner(rig, node)
        val won = learner.claim(ClaimTarget("record:web-1:1"), ada)
        // The bridge posted once, then died with its link: nothing posts again.
        val board = MutableStateFlow(BoardState.EMPTY.piece(BoardState.EMPTY.postPresence(ReplicaId("mcp-ada"), entry(ada, node), clock = 1)))
        val roster = LivePresence(board, rig.clock, backgroundScope) { throw it }
        hostDepartures(voter, board, rig.clock).start(backgroundScope)
        val liveAtDrop = async {
            withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) {
                voter.membership.first { node !in it.allMembers }
                roster.live()
            }
        }

        kill()
        val after = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) { voter.table.first { it.retired(ada) } }
        val aged = liveAtDrop.await()
        assertAll(
            { assertIs<ClaimOutcome.Won>(won, "rig: ada held a claim") },
            { assertEquals(emptyList(), aged, "rig: by the drop, the entry had aged out of the live roster") },
            { assertEquals(DepartReason.Crashed, after?.departReason(ada), "departed Crashed all the same") },
        )
        voter.close()
    }

    /**
     * Presence entries are never pruned and a booted host starts with nothing proposed, so the
     * quiet check would propose `Depart(Quiet)` for every agent that ever posted. An agent the
     * board already lists as departed gets none: each would cost a durable append and replication.
     * A proposal shows as a step of the table's applied index, even when the command is rejected.
     */
    @Test
    fun theHostProposesNoDepartForAnAgentTheBoardListsAsDeparted() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val ada = PeerIdentity.agent("ada", Random(1))
        voter.depart(ada, DepartReason.Quiet)
        val retired = voter.table.first { it.retired(ada) }
        val posted = BoardState.EMPTY.postPresence(ReplicaId("mcp-ada"), staleEntry(ada), clock = 1)
        val board = MutableStateFlow(
            BoardState.EMPTY.piece(posted).let { it.piece(it.mirrorClaims(ReplicaId("host-1"), retired)) },
        )
        hostDepartures(voter, board, rig.clock).start(backgroundScope)

        delay(Departures.CHECK_EVERY * 50)
        assertAll(
            { assertEquals(setOf(ada), board.value.departures().map { it.identity }.toSet(), "rig: the board lists ada departed") },
            { assertEquals(listOf(ada), board.value.latestPresence().map { it.identity }, "rig: ada's presence is still there") },
            { assertEquals(retired.appliedIndex, voter.table.value.appliedIndex, "nothing was proposed") },
        )
        voter.close()
    }

    private fun staleEntry(identity: PeerIdentity) =
        PresenceEntry(identity, NodeId("mcp-ada-1"), status = null, lastActedAt = Instant.fromEpochMilliseconds(0), waiting = false)

    private fun entry(identity: PeerIdentity, node: NodeId) =
        PresenceEntry(identity, node, status = null, lastActedAt = Instant.DISTANT_FUTURE, waiting = false)

    /** A learner on its own job, and the kill that ends it the way a dead process ends its link. */
    private fun TestScope.learner(rig: ClaimsRig, node: NodeId): Pair<ClaimsClient, suspend () -> Unit> {
        val job = Job(backgroundScope.coroutineContext[Job])
        val client = rig.client(node, CoroutineScope(backgroundScope.coroutineContext + job))
        return client to { job.cancelAndJoin() }
    }
}
