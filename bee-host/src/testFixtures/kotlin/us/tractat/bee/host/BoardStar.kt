package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import us.tractat.bee.core.BOARD_PAYLOAD_BYTES
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.SpokeLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The board relay in memory, under virtual time: a [BoardRelay] whose hub accepts in-memory
 * connections, and [BoardClient]s whose [SpokeLoom]s dial into it. It's a star, as over real
 * sockets: a spoke's only link is the hub. Each loom's spoke end runs through a [WireTap], kept
 * in [taps] under the loom's name across its redials.
 */
class BoardStar(private val test: TestScope) {
    val source = InMemoryConnectionSource()
    val hubEnds = mutableListOf<Connection>()
    val taps = mutableMapOf<String, WireTap>()
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    lateinit var relay: BoardRelay

    suspend fun start() {
        relay = BoardRelay.hosted(test.backgroundScope, source, dispatcher, ReplicaId("host-test"), clock, Random(0), config = CONFIG)
    }

    fun loom(name: String, scope: CoroutineScope = test.backgroundScope) = SpokeLoom(scope, name, Random(name.hashCode()), clock) { id, _ ->
        val (hubEnd, spokeEnd) = connectionPair()
        hubEnds += hubEnd
        source.offer(hubEnd)
        peerMesh(id, listOf(taps.getOrPut(name) { WireTap() }.wrap(spokeEnd)), dispatcher)
    }

    /** A board client of [author]'s through [loom], sending no payload over [maxPayloadBytes]. */
    fun client(
        author: ReplicaId,
        loom: SpokeLoom = loom(author.value),
        scope: CoroutineScope = test.backgroundScope,
        maxPayloadBytes: Int = BOARD_PAYLOAD_BYTES,
    ) = BoardClient(author, loom, Rendezvous.Existing(InMemoryTag("board")), scope, clock, config = CONFIG, maxPayloadBytes = maxPayloadBytes)

    companion object {
        /** The board's tuning under virtual time. */
        val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)
    }
}
