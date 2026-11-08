@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.bee.core.BoardFixtures.SURFACE
import us.tractat.bee.core.BoardFixtures.opened
import us.tractat.bee.core.BoardFixtures.str
import us.tractat.bee.core.BoardFixtures.values
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)

/** How long a slowed spoke's frame takes to leave for the hub. */
private val SLOW_UPLINK: Duration = 500.milliseconds

/** Long enough for any write to be acknowledged on a fast link, and short of eviction. */
private val SETTLE: Duration = 10.seconds

/**
 * The board's hub in memory, under virtual time: a Quilter over kuilt's hosted overlay, which
 * acknowledges what reaches it, as bee-host's relay does. Its spokes' frames to it can be slowed,
 * as bee-companion's `CompanionRig.slowUplink` does, so a write sits unacknowledged for a while.
 */
private class AckHub(private val test: TestScope) {
    private val source = InMemoryConnectionSource()
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val hubEnds = mutableListOf<Connection>()
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    lateinit var quilter: Quilter<BoardState>

    /** While true, every frame a spoke sends takes [SLOW_UPLINK] to leave. */
    var slow = false

    suspend fun start() {
        val seam = test.backgroundScope.hostedOverlay(PeerId("hub"), source, dispatcher, Random(0), clock)
        quilter = Quilter(
            replica = ReplicaId("hub"),
            seam = seam,
            initial = BoardState.EMPTY,
            messageSerializer = QuiltMessage.serializer(BoardState.serializer()),
            scope = test.backgroundScope,
            config = CONFIG,
        )
    }

    /** A client on [opened]'s board, whose every connection is a spoke into this hub. */
    fun client(): BoardClient {
        val loom = SpokeLoom(test.backgroundScope, "alex", Random(1), clock) { id, _ ->
            val (hubEnd, spokeEnd) = connectionPair()
            hubEnds += hubEnd
            source.offer(hubEnd)
            peerMesh(id, listOf(Slow(spokeEnd) { slow }), dispatcher)
        }
        return BoardClient(A, loom, Rendezvous.Existing(InMemoryTag("board")), test.backgroundScope, clock, opened(), CONFIG, Random(0))
    }

    /** Drops every spoke's link, as a relay that goes away does. */
    suspend fun tear() {
        hubEnds.forEach { it.close() }
    }

    /** The values the hub holds at `/note`, none before the surface reaches it. */
    fun notes(): Set<JsonElement> = notes(quilter.state.value)

    /** A link whose frames take [SLOW_UPLINK] to leave while [slow]. */
    private class Slow(private val base: Connection, private val slow: () -> Boolean) : Connection by base {
        override suspend fun send(frame: ByteArray) {
            if (slow()) delay(SLOW_UPLINK)
            base.send(frame)
        }
    }
}

private fun notes(state: BoardState): Set<JsonElement> =
    if (state.surfaceDoc(SURFACE) == null || BoardFixtures.node(state, "/note") == null) emptySet() else values(state, "/note")

/** A client online through [hub], once the hub holds its board. */
private suspend fun online(hub: AckHub): BoardClient {
    val client = hub.client().also { it.connect() }
    client.online.first { it }
    hub.quilter.state.first { it.surfaceDoc(SURFACE) != null }
    return client
}

private suspend fun BoardClient.note(value: String) = mutate { it.updateData(A, SURFACE, "/note", str(value)) }

/**
 * [BoardClient.awaitAcknowledged]: a process waits on it before it ends, so a write it made
 * survives it (bee-companion's close on SIGTERM).
 */
class BoardClientAcknowledgedTest {
    /**
     * On a link that takes [SLOW_UPLINK] to carry the write, the wait is still open just short of
     * that, while the hub hasn't the write, and returns true only once the hub has it.
     */
    @Test
    fun aWriteIsAcknowledgedOnlyOnceTheHubHasIt() = runTest(StandardTestDispatcher()) {
        val hub = AckHub(this).apply { start() }
        val alex = online(hub)
        hub.slow = true
        alex.note("acked")
        var hubHadItOnReturn: Boolean? = null
        val acknowledged = async { alex.awaitAcknowledged().also { hubHadItOnReturn = str("acked") in hub.notes() } }
        advanceTimeBy(SLOW_UPLINK - 1.milliseconds)
        runCurrent()
        val openBeforeTheHubHasIt = !acknowledged.isCompleted
        val hubHadItEarly = str("acked") in hub.notes()
        val result = withTimeoutOrNull(SETTLE) { acknowledged.await() }
        assertAll(
            { assertFalse(hubHadItEarly, "precondition: the slowed write hasn't reached the hub") },
            { assertTrue(openBeforeTheHubHasIt, "the wait is open while the hub hasn't the write") },
            { assertEquals(true, result, "the hub acknowledged the write") },
            { assertEquals(true, hubHadItOnReturn, "the hub had the write when the wait returned") },
        )
    }

    /**
     * A transform that declines writes nothing, so the Quilter numbers nothing: counted, it would
     * leave the wait one write short of anything the hub could acknowledge.
     */
    @Test
    fun aDeclinedWriteIsNotCounted() = runTest(StandardTestDispatcher()) {
        val hub = AckHub(this).apply { start() }
        val alex = online(hub)
        alex.note("written")
        val declined = alex.mutateOrSkip { null }
        val acknowledged = withTimeoutOrNull(SETTLE) { alex.awaitAcknowledged() }
        assertAll(
            { assertFalse(declined, "precondition: the transform declined") },
            { assertEquals(true, acknowledged, "the one real write is acknowledged") },
            { assertEquals(setOf(str("written")), hub.notes(), "the hub has the real write") },
        )
    }

    /**
     * Offline there's nothing to wait for: an offline write rides the next connection's
     * FullState. A client whose connection parted with a write unacknowledged doesn't wait for it.
     */
    @Test
    fun offlineReturnsFalseAtOnce() = runTest(StandardTestDispatcher()) {
        val hub = AckHub(this).apply { start() }
        val alex = online(hub)
        hub.slow = true
        alex.note("unacknowledged")
        alex.disconnect()
        alex.online.first { !it }
        alex.note("offline")
        val before = currentTime
        val acknowledged = alex.awaitAcknowledged()
        assertAll(
            { assertFalse(acknowledged, "offline is not acknowledged") },
            { assertEquals(before, currentTime, "it returned without waiting") },
        )
    }

    /**
     * A connection that ends mid-wait ends the wait, false, as soon as it ends: its writes can no
     * longer be acknowledged on it. The client redials, and the wait doesn't move to that.
     */
    @Test
    fun aConnectionEndingMidWaitReturnsFalse() = runTest(StandardTestDispatcher()) {
        val hub = AckHub(this).apply { start() }
        val alex = online(hub)
        hub.slow = true
        alex.note("lost")
        val acknowledged = async { alex.awaitAcknowledged() }
        advanceTimeBy(100.milliseconds)
        runCurrent()
        val openBeforeTheTear = !acknowledged.isCompleted
        hub.tear()
        val tornAt = currentTime
        alex.online.first { !it }
        runCurrent()
        val endedAtTheTear = acknowledged.isCompleted && currentTime < tornAt + SLOW_UPLINK
        assertAll(
            { assertTrue(openBeforeTheTear, "precondition: the wait is open before the tear") },
            { assertTrue(endedAtTheTear, "the wait ended with the connection, before the write could land") },
            { assertFalse(acknowledged.await(), "a connection that ended is not an acknowledgement") },
        )
    }

    /**
     * Each connection's Quilter numbers its writes from 1, so the count restarts with it. Carried
     * over, the new connection's one write would wait on numbers its Quilter never publishes.
     */
    @Test
    fun aReconnectRestartsTheCount() = runTest(StandardTestDispatcher()) {
        val hub = AckHub(this).apply { start() }
        val alex = online(hub)
        repeat(3) { alex.note("first-$it") }
        val firstAcknowledged = withTimeoutOrNull(SETTLE) { alex.awaitAcknowledged() }
        alex.disconnect()
        alex.online.first { !it }
        alex.connect()
        alex.online.first { it }
        alex.note("second")
        val acknowledged = withTimeoutOrNull(SETTLE) { alex.awaitAcknowledged() }
        assertAll(
            { assertEquals(true, firstAcknowledged, "precondition: the first connection's writes are acknowledged") },
            { assertEquals(true, acknowledged, "the second connection's one write is acknowledged") },
            { assertEquals(setOf(str("second")), hub.notes(), "the hub has it") },
        )
    }
}
