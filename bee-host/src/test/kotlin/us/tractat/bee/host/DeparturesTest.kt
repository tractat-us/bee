@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.ClaimOutcome
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DeparturesTest {
    private val ada = PeerIdentity.agent("ada", Random(1))
    private val bo = PeerIdentity.agent("bo", Random(2))
    private val iain = PeerIdentity.human("iain")

    /**
     * A board link lost for the reconnect window (`PeerLost`) retires the agent whose presence
     * names it, through the voter, so the claim it held shows orphaned.
     */
    @Test
    fun lostLinkRetiresTheAgentAndOrphansItsClaims() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val target = ClaimTarget("record:web-1:1")
        val won = rig.client(NodeId("claims-a")).claim(target, ada)
        val board = Board(this, entry(ada, "mcp-ada-1"))
        board.start(depart = voter::depart)

        board.lose("mcp-ada-1")
        val after = voter.table.first { it.retired(ada) }
        assertAll(
            { assertIs<ClaimOutcome.Won>(won, "rig: ada held the target") },
            { assertEquals(DepartReason.Crashed, after.departReason(ada)) },
            { assertEquals(ClaimStatus.Orphaned(ada, (won as ClaimOutcome.Won).token), after.status(target)) },
        )
        voter.close()
    }

    /**
     * An agent that redialled has a presence entry naming its new link, so the old link's loss
     * retires nobody. The new link's loss, as a control, does.
     */
    @Test
    fun reconnectedPeerIsNotRetired() = runTest {
        val board = Board(this, entry(ada, "mcp-ada-2"))
        val departed = board.start()

        board.lose("mcp-ada-1")
        val afterOldLink = departed.toList()
        board.lose("mcp-ada-2")
        assertAll(
            { assertEquals(emptyList(), afterOldLink, "the old link's loss retires nobody") },
            { assertEquals(listOf(ada to DepartReason.Crashed), departed, "control: the current link's loss does") },
        )
    }

    /**
     * An agent whose last act is 20 minutes old, and that isn't waiting, is retired `Quiet`: not
     * at 19 minutes, and by 20 minutes plus one check.
     */
    @Test
    fun quietAgentIsRetiredAfterTwentyMinutes() = runTest {
        val board = Board(this, entry(ada, "mcp-ada-1", lastActedAt = board0))
        val departed = board.start()

        advanceTimeBy(19.minutes)
        runCurrent()
        val at19 = departed.toList()
        advanceTimeBy(1.minutes + Departures.CHECK_EVERY)
        runCurrent()
        assertAll(
            { assertEquals(emptyList(), at19, "not quiet before 20 minutes") },
            { assertEquals(listOf(ada to DepartReason.Quiet), departed, "quiet by 20 minutes and one check") },
        )
    }

    /** A waiting agent is never quiet, however long it waits. A quiet agent beside it, the control, is. */
    @Test
    fun waitingAgentIsNeverQuiet() = runTest {
        val board = Board(
            this,
            entry(ada, "mcp-ada-1", lastActedAt = board0, waiting = true),
            entry(bo, "mcp-bo-1", lastActedAt = board0),
        )
        val departed = board.start()

        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(listOf(bo to DepartReason.Quiet), departed, "only the agent not waiting is quiet")
    }

    /**
     * A human's lost link and long silence retire nobody (spec §6.4). An agent on the same board,
     * the control, is retired for each.
     */
    @Test
    fun humansNeverDepart() = runTest {
        val board = Board(
            this,
            entry(iain, "web-iain-1", lastActedAt = board0),
            entry(ada, "mcp-ada-1"),
            entry(bo, "mcp-bo-1", lastActedAt = board0),
        )
        val departed = board.start()

        board.lose("web-iain-1")
        board.lose("mcp-ada-1")
        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(
            listOf(ada to DepartReason.Crashed, bo to DepartReason.Quiet),
            departed,
            "the agents depart, the human never",
        )
    }

    /**
     * A `Cleared` departure stands: when the cleared agent's link then drops, the `Crashed` that
     * `Departures` proposes applies as rejected, so the table still says why it left. The `/clear`
     * path itself, from `POST /retire` to the committed `Depart`, is bee-mcp's
     * `BridgeClaimsTest.postRetireDepartsClearedAndOrphansTheClaims`.
     */
    @Test
    fun clearRetiresWithClearedThroughRaft() = runTest(timeout = 120.seconds) {
        val rig = ClaimsRig(this)
        val voter = rig.startVoter()
        val board = Board(this, entry(ada, "mcp-ada-1"))
        val proposed = board.start(depart = voter::depart)

        voter.depart(ada, DepartReason.Cleared)
        val cleared = voter.table.first { it.retired(ada) }
        board.lose("mcp-ada-1")
        val through = voter.table.first { it.appliedIndex > cleared.appliedIndex }
        assertAll(
            { assertEquals(DepartReason.Cleared, cleared.departReason(ada)) },
            { assertEquals(listOf(ada to DepartReason.Crashed), proposed, "rig: the lost link did propose Crashed") },
            { assertEquals(DepartReason.Cleared, through.departReason(ada), "the later Crashed changed nothing") },
        )
        voter.close()
    }

    private val board0 = Instant.fromEpochMilliseconds(0)

    private fun entry(
        identity: PeerIdentity,
        link: String,
        lastActedAt: Instant = Instant.DISTANT_FUTURE,
        waiting: Boolean = false,
    ) = PresenceEntry(identity, link, status = null, lastActedAt = lastActedAt, waiting = waiting)

    /** A scripted board: fixed presence entries, and a partition-event stream the test drives. */
    private class Board(private val test: TestScope, vararg entries: PresenceEntry) {
        private val presence = entries.toList()
        private val events = MutableSharedFlow<PartitionEvent>(extraBufferCapacity = 16)
        private val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }

        /** Starts [Departures] on the test's background scope; returns what it proposed, in order. */
        fun start(
            quietAfter: Duration = 20.minutes,
            depart: (suspend (PeerIdentity, DepartReason) -> Unit)? = null,
        ): MutableList<Pair<PeerIdentity, DepartReason>> {
            val proposed = mutableListOf<Pair<PeerIdentity, DepartReason>>()
            Departures(events, { presence }, { who, why -> proposed += who to why; depart?.invoke(who, why) }, quietAfter, clock)
                .start(test.backgroundScope)
            test.testScheduler.runCurrent()
            return proposed
        }

        suspend fun lose(link: String) {
            events.emit(PartitionEvent.PeerLost(PeerId(link), clock()))
            test.testScheduler.runCurrent()
        }
    }
}
