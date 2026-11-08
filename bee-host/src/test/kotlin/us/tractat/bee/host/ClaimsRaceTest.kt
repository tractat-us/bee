package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.PeerIdentity
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClaimsRaceTest {
    /**
     * Two learners claim each target at once, over [ROUNDS] rounds. Each round ends with one
     * `Won` and one `Lost` naming the winner, and the mirror holds the winner under the winner's
     * token. A seeded delay before each claim varies who reaches the voter first, so each client
     * wins some rounds. At the end both learners hold the voter's table.
     */
    @Test
    fun twoClientsRaceAndExactlyOneWins() = runTest(timeout = 300.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val b = rig.client(NodeId("claims-b"))
        val jitter = Random(7)

        val rounds = (1..ROUNDS).map { round ->
            val target = ClaimTarget("record:race:$round")
            val (delayA, delayB) = List(2) { jitter.nextLong(JITTER_MS).milliseconds }
            val outcomes = listOf(
                async { delay(delayA); a.claim(target, alice) },
                async { delay(delayB); b.claim(target, bob) },
            ).awaitAll()
            val mirrored = rig.mirrored.first { it.status(target) is ClaimStatus.Held }.status(target) as ClaimStatus.Held
            outcomes to mirrored
        }

        val expected = rounds.map { (_, mirrored) ->
            val won = ClaimOutcome.Won(mirrored.token)
            if (mirrored.holder == alice) listOf(won, ClaimOutcome.Lost(alice)) else listOf(ClaimOutcome.Lost(bob), won)
        }
        val winners = rounds.map { (_, mirrored) -> mirrored.holder }
        val through = voter.table.value.appliedIndex
        val aTable = a.table.first { it.appliedIndex >= through }
        val bTable = b.table.first { it.appliedIndex >= through }
        rig.report(
            "race: $ROUNDS rounds, log committed through $through, " +
                "latencies a=${a.latencies.value.summary()} b=${b.latencies.value.summary()}, " +
                "a won ${winners.count { it == alice }}, b won ${winners.count { it == bob }}",
        )
        assertAll(
            { assertTrue(alice in winners && bob in winners, "rig: the jitter lets each client win some rounds") },
            { assertEquals(expected, rounds.map { it.first }, "each round is one Won under the mirrored token and one Lost naming the winner") },
            { assertEquals(voter.table.value, aTable, "a holds the voter's table") },
            { assertEquals(voter.table.value, bTable, "b holds the voter's table") },
        )
        voter.close()
    }

    /**
     * Fit check, spike 4 Step 1: a learner keeps receiving the committed log after a second
     * learner is admitted. The voter's `Depart` after both joined must reach both tables.
     */
    @Test
    fun aSecondLearnerLeavesTheFirstOnTheLog() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val first = a.claim(ClaimTarget("record:fit:1"), alice)
        val b = rig.client(NodeId("claims-b"))
        val second = b.claim(ClaimTarget("record:fit:2"), bob)
        voter.depart(bob, DepartReason.Crashed)
        // The Depart's own index: `depart` returns before the voter's fold reaches it.
        val through = voter.table.first { it.retired(bob) }.appliedIndex
        val aSaw = withTimeoutOrNull(10.seconds) { a.table.first { it.appliedIndex >= through } }
        val bSaw = withTimeoutOrNull(10.seconds) { b.table.first { it.appliedIndex >= through } }
        rig.report(
            "two learners: voter through $through, a folded through ${a.table.value.appliedIndex}, " +
                "b through ${b.table.value.appliedIndex}; outcomes $first, $second; " +
                "voter membership ${voter.membership.value}",
        )
        assertAll(
            {
                assertEquals(
                    setOf(NodeId("claims-a"), NodeId("claims-b")),
                    voter.membership.value.learners,
                    "both learners are in the voter's configuration",
                )
            },
            { assertIs<ClaimOutcome.Won>(first, "a's claim, before b joined") },
            { assertIs<ClaimOutcome.Won>(second, "b's claim") },
            { assertNotNull(bSaw, "the newest learner folds the log") },
            { assertNotNull(aSaw, "the first learner still folds the log after the second joined") },
        )
        voter.close()
    }

    /**
     * A learner that goes away leaves the voter's config once it has been gone for
     * [ClaimsVoter.LEARNER_GRACE], and not before. The learner that stays keeps claiming.
     *
     * The removal's virtual time is the signal: the overload without a grace never removes (null),
     * and a shorter grace removes early (elapsed under the grace).
     */
    // Red on kuilt 0.7.0-dev.1692 (tractat-us/kuilt#3127): ServerCluster held each admitted room
    // until its coroutine was cancelled, so a learner's own disconnect never started the grace.
    // Green from 0.7.0-dev.1693 (kuilt#3130): the admission ends when the learner leaves the roster.
    @Test
    fun aLearnerGoneForTheGraceLeavesTheConfig() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val alice = PeerIdentity.agent("alice", Random(1))
        val bob = PeerIdentity.agent("bob", Random(2))
        val a = rig.client(NodeId("claims-a"))
        val bJob = Job(backgroundScope.coroutineContext[Job])
        val bScope = CoroutineScope(backgroundScope.coroutineContext + bJob)
        val b = rig.client(NodeId("claims-b"), bScope)
        val joined = listOf(a.claim(ClaimTarget("record:grace:1"), alice), b.claim(ClaimTarget("record:grace:2"), bob))
        val bothIn = voter.membership.value.learners

        val gone = testScheduler.currentTime
        bJob.cancelAndJoin()
        val removed = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2) {
            voter.membership.first { NodeId("claims-b") !in it.learners }
        }
        val elapsed = (testScheduler.currentTime - gone).milliseconds
        val after = a.claim(ClaimTarget("record:grace:3"), alice)
        rig.report("grace: b removed after $elapsed, membership ${voter.membership.value}")
        assertAll(
            { assertEquals(List(2) { true }, joined.map { it is ClaimOutcome.Won }, "rig: both learners were admitted") },
            { assertEquals(setOf(NodeId("claims-a"), NodeId("claims-b")), bothIn, "rig: both learners were in the config") },
            { assertEquals(setOf(NodeId("claims-a")), removed?.learners, "the gone learner leaves; the other stays") },
            { assertTrue(elapsed >= ClaimsVoter.LEARNER_GRACE, "not removed before the grace, was $elapsed") },
            { assertIs<ClaimOutcome.Won>(after, "the remaining learner still claims") },
        )
        voter.close()
    }

    private companion object {
        const val ROUNDS = 50

        /** The spread of each claim's start, in ms: wider than one learner's commit lag over the other's. */
        const val JITTER_MS = 200L
    }
}

private fun List<kotlin.time.Duration>.summary(): String =
    if (isEmpty()) "none" else "min ${min()} max ${max()} n $size"
