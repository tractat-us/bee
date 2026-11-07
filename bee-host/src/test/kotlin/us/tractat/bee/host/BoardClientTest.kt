@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.ActionRecord
import us.tractat.bee.core.Basis
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardClientTestHook
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val TEST_CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)
private const val SURFACE = "lunch"
private val ALEX = ReplicaId("web-alex")
private val SAM = ReplicaId("web-sam")
private val OPENED_AT = Instant.parse("2026-11-06T12:00:00Z")

/**
 * The board relay in memory, under virtual time: a [BoardRelay] whose hub accepts in-memory
 * connections, and [BoardClient]s whose [SpokeLoom]s dial into it. It's a star, as over real
 * sockets: a spoke's only link is the hub.
 */
private class Star(private val test: TestScope) {
    val source = InMemoryConnectionSource()
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    lateinit var relay: BoardRelay

    suspend fun start() {
        relay = BoardRelay.hosted(test.backgroundScope, source, dispatcher, ReplicaId("host-test"), clock, Random(0), config = TEST_CONFIG)
    }

    fun loom(name: String, scope: CoroutineScope = test.backgroundScope) = SpokeLoom(scope, name, Random(name.hashCode()), clock) { id, _ ->
        val (hubEnd, spokeEnd) = connectionPair()
        source.offer(hubEnd)
        peerMesh(id, listOf(spokeEnd), dispatcher)
    }

    fun client(author: ReplicaId, loom: SpokeLoom = loom(author.value), scope: CoroutineScope = test.backgroundScope) =
        BoardClient(author, loom, Rendezvous.Existing(InMemoryTag("board")), scope, clock, config = TEST_CONFIG)
}

private fun meta() = SurfaceMeta("Lunch", NOTIONS_CATALOG_ID, PeerIdentity.human("iain"), OPENED_AT)

private val root: JsonObject = buildJsonObject {
    put("id", "root")
    put("component", "Text")
    put("text", "Lunch?")
}

private fun data(state: BoardState): JsonObject? = state.surfaceDoc(SURFACE)?.toSurfaceView(SURFACE)?.data

private fun vote(state: BoardState) = ActionRecord(
    surfaceId = SURFACE,
    componentId = "root",
    actor = PeerIdentity.human("alex"),
    name = "vote",
    context = JsonObject(emptyMap()),
    basis = Basis.capture(state, SURFACE),
    at = OPENED_AT,
)

private fun str(s: String): JsonElement = JsonPrimitive(s)

private fun Job.liveDescendants(): Int = children.sumOf { (if (it.isActive) 1 else 0) + it.liveDescendants() }

class BoardClientTest {
    /** Edits travel spoke → hub → spoke on the gossip flood, well inside one anti-entropy round. */
    @Test
    fun twoClientsConvergeThroughTheHub() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        val sam = star.client(SAM)
        alex.connect()
        sam.connect()
        runCurrent()
        alex.mutate { it.openSurface(ALEX, SURFACE, meta(), listOf(root), JsonObject(emptyMap())) }
        advanceTimeBy(100.milliseconds)
        runCurrent()
        sam.mutate { it.updateData(SAM, SURFACE, "/note", str("from sam")) }
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertAll(
            { assertTrue(alex.online.value && sam.online.value, "precondition: both online") },
            { assertEquals(str("from sam"), data(alex.state.value)?.get("note"), "sam's edit reached alex") },
            { assertEquals(alex.state.value, sam.state.value) },
            { assertEquals(alex.state.value, star.relay.state.value) },
        )
    }

    /**
     * An offline edit reaches the other spoke by the merge echo, inside one anti-entropy round,
     * under the author it was minted with, and only once: a record re-applied on reconnect would
     * carry a new dot and show up twice.
     */
    @Test
    fun offlineEditMergesOnReconnectUnderTheStableAuthor() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        val sam = star.client(SAM)
        alex.connect()
        sam.connect()
        runCurrent()
        alex.mutate { it.openSurface(ALEX, SURFACE, meta(), listOf(root), JsonObject(emptyMap())) }
        runCurrent()
        val firstLink = alex.link.value
        alex.disconnect()
        runCurrent()
        alex.mutate { it.appendRecord(ALEX, vote(it)) }
        alex.mutate { it.updateData(ALEX, SURFACE, "/note", str("offline")) }
        val minted = alex.state.value.records(SURFACE).single().first.dot
        advanceTimeBy(3.seconds)
        runCurrent()
        assertAll(
            { assertFalse(alex.online.value, "precondition: alex is offline") },
            { assertNull(alex.link.value, "precondition: an offline client has no link") },
            { assertTrue(sam.state.value.records(SURFACE).isEmpty(), "precondition: sam can't have alex's offline record yet") },
        )
        alex.connect()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        val samRecords = sam.state.value.records(SURFACE)
        assertAll(
            { assertTrue(alex.online.value) },
            { assertNotNull(alex.link.value) },
            { assertTrue(alex.link.value != firstLink, "every connection dials under a fresh fabric id") },
            { assertEquals(listOf(minted), samRecords.map { it.first.dot }, "one record, under the dot minted offline") },
            { assertEquals(ALEX, minted.replica) },
            { assertEquals(str("offline"), data(sam.state.value)?.get("note")) },
            { assertEquals(alex.state.value, sam.state.value) },
        )
    }

    /**
     * The identity-guarded mirror. On one test thread the race is unreachable: a parted
     * connection's job is cancelled under the lock, so its mirror pump never runs again. It is
     * reachable only when a mirror body is already past its suspension point on another thread
     * as the part runs. So this drives that body directly, with the parted connection, which is
     * exactly what such a late body does. Without the `live === connection` check, it reds.
     */
    @OptIn(BoardClientTestHook::class)
    @Test
    fun lateEmissionFromAPartedQuilterNeverOverwritesTheHeldState() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val alex = star.client(ALEX)
        alex.connect()
        runCurrent()
        alex.mutate { it.openSurface(ALEX, SURFACE, meta(), listOf(root), JsonObject(emptyMap())) }
        runCurrent()
        val stale = assertNotNull(alex.liveConnection(), "precondition: alex is connected")
        alex.disconnect()
        runCurrent()
        alex.mutate { it.updateData(ALEX, SURFACE, "/note", str("offline")) }
        assertNull(alex.liveConnection(), "precondition: the connection parted")
        alex.mirror(stale)
        assertAll(
            { assertEquals(str("offline"), data(alex.state.value)?.get("note"), "a late mirror must not drop the offline edit") },
            { assertEquals(listOf(SURFACE), alex.state.value.openSurfaces()) },
        )
    }

    /**
     * Ten connect/disconnect cycles leave nothing running on the loom's scope (each weave's
     * gossip loops) or on the client's (each connection's Quilter and pumps).
     */
    @Test
    fun reconnectLeaksNoGossipCoroutines() = runTest(StandardTestDispatcher()) {
        val star = Star(this).apply { start() }
        val loomJob = SupervisorJob(backgroundScope.coroutineContext.job)
        val clientJob = SupervisorJob(backgroundScope.coroutineContext.job)
        val alex = star.client(ALEX, star.loom("alex", backgroundScope + loomJob), backgroundScope + clientJob)
        runCurrent()
        val idle = clientJob.liveDescendants()
        alex.connect()
        runCurrent()
        val loopsWhileConnected = loomJob.liveDescendants()
        val clientWhileConnected = clientJob.liveDescendants()
        alex.disconnect()
        runCurrent()
        repeat(9) { i ->
            alex.connect()
            runCurrent()
            alex.mutate { if (i == 0) it.openSurface(ALEX, SURFACE, meta(), listOf(root), JsonObject(emptyMap())) else it.updateData(ALEX, SURFACE, "/n", JsonPrimitive(i)) }
            runCurrent()
            alex.disconnect()
            runCurrent()
        }
        assertAll(
            { assertTrue(loopsWhileConnected > 0, "precondition: a weave runs loops on the loom scope ($loopsWhileConnected)") },
            { assertTrue(clientWhileConnected > idle, "precondition: a connection runs on the client scope ($clientWhileConnected vs $idle)") },
            { assertEquals(0, loomJob.liveDescendants(), "live gossip loops after ten cycles") },
            { assertEquals(idle, clientJob.liveDescendants(), "live client jobs after ten cycles") },
            { assertFalse(alex.online.value) },
        )
    }
}
