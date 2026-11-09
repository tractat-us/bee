@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.NodeId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [Departures] judges an agent by its newest entry, whichever slot holds it (bee#126). An HTTP
 * session that comes back from a host restart as itself posts from a new replica on a new claims
 * node, while a peer that stayed connected pushes the old host's slot for it back onto the board:
 * one identity, two slots. The old slot's node loss and its old `lastActedAt` say nothing about
 * the live session.
 */
class DeparturesNewestEntryTest {
    private val alex = PeerIdentity.agent("alex-claude", Random(1))
    private val slots = MutableStateFlow(EphemeralMap.empty<PresenceEntry>())
    private val events = MutableSharedFlow<PartitionEvent>(extraBufferCapacity = 16)
    private val proposed = mutableListOf<Pair<PeerIdentity, DepartReason>>()

    @Test
    fun theOldSlotsNodeLossLeavesTheIdentityLiveOnItsNewNodeAlone() = runTest {
        start()
        post(NEW, NodeId("claims-new"), now(), clock = 1)
        runCurrent()
        events.emit(PartitionEvent.PeerLost(PeerId("claims-old"), Instant.fromEpochMilliseconds(0)))
        runCurrent()
        assertEquals(emptyList(), proposed, "the old node's loss retires nobody: the identity is live on a new node")
    }

    @Test
    fun theNewestSlotsNodeLossStillDepartsItCrashed() = runTest {
        start()
        post(NEW, NodeId("claims-new"), now(), clock = 1)
        runCurrent()
        events.emit(PartitionEvent.PeerLost(PeerId("claims-new"), Instant.fromEpochMilliseconds(0)))
        runCurrent()
        assertEquals(listOf(alex to DepartReason.Crashed), proposed, "control: losing the node it posts from now is a crash")
    }

    @Test
    fun theOldSlotsOldActNeverMakesALiveIdentityQuiet() = runTest {
        start()
        (1L..50L).forEach { clock ->
            post(NEW, NodeId("claims-new"), now(), clock)
            advanceTimeBy(30.seconds)
            runCurrent()
        }
        assertEquals(emptyList(), proposed, "acting every 30 s on its new slot, it is never quiet")
    }

    @Test
    fun anIdentityQuietOnEverySlotDepartsQuiet() = runTest {
        start()
        post(NEW, NodeId("claims-new"), now(), clock = 1)
        advanceTimeBy(21.minutes)
        runCurrent()
        assertEquals(listOf(alex to DepartReason.Quiet), proposed, "control: quiet on its newest slot too")
    }

    /**
     * Before its client comes back, the pushed-back slot is alex's only entry: its node's loss,
     * its silence and its unseen claim are the last host's. Kept for its client, it is left alone.
     */
    @Test
    fun anAgentKeptForItsClientIsLeftAloneByEveryRule() = runTest {
        start(waiting = setOf(alex), holders = setOf(alex))
        events.emit(PartitionEvent.PeerLost(PeerId("claims-old"), Instant.fromEpochMilliseconds(0)))
        advanceTimeBy(21.minutes)
        runCurrent()
        assertEquals(emptyList(), proposed, "lost link, boot check and quiet rule all leave it to its own deadline")
    }

    @Test
    fun theSameAgentNotKeptIsDepartedAsBefore() = runTest {
        start(waiting = emptySet(), holders = setOf(alex))
        events.emit(PartitionEvent.PeerLost(PeerId("claims-old"), Instant.fromEpochMilliseconds(0)))
        runCurrent()
        assertEquals(listOf(alex to DepartReason.Crashed), proposed, "control: a slot that is its only entry, whose node is lost, is a crash")
    }

    /** The old host's slot for [alex], as a surviving peer pushes it back: old node, last act at 0. */
    private fun TestScope.start(waiting: Set<PeerIdentity> = emptySet(), holders: Set<PeerIdentity> = emptySet()) {
        post(OLD, NodeId("claims-old"), Instant.fromEpochMilliseconds(0), clock = 1)
        val clock = { now() }
        Departures(events, { slots.value.entries.values.mapNotNull { it.value } }, slots, { holders }, { who, why -> proposed += who to why }, 20.minutes, clock) { waiting }
            .start(backgroundScope)
        runCurrent()
        // The resumed session posts after the old one last acted, never in the same millisecond.
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private fun TestScope.now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private fun post(replica: ReplicaId, node: NodeId, actedAt: Instant, clock: Long) {
        slots.update { it.put(replica, PresenceEntry(alex, node, null, actedAt, false), clock) }
    }

    private companion object {
        val OLD = ReplicaId("mcp-old")
        val NEW = ReplicaId("mcp-new")
    }
}
