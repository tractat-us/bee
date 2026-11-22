package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The board relay in memory, under virtual time: a [BoardRelay] whose hub accepts in-memory
 * connections, and [BoardClient]s whose [SpokeLoom]s dial into it. It's a star, as over real
 * sockets: a spoke's only link is the hub. Each loom's spoke end runs through a [WireTap], kept
 * in [taps] under the loom's name across its redials.
 *
 * @param config the Quilter tuning of the relay and of every client: [CONFIG] unless a test pins another
 * @param lag how long each frame takes to cross a link, either way, in order: how a host that has
 *   fallen behind its inbound frames looks to its spokes
 */
class BoardStar(
    private val test: TestScope,
    private val config: QuilterConfig = CONFIG,
    private val lag: Duration = Duration.ZERO,
) {
    val source = InMemoryConnectionSource()
    val hubEnds = mutableListOf<Connection>()
    val taps = mutableMapOf<String, WireTap>()
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) }
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    lateinit var relay: BoardRelay

    suspend fun start() {
        relay = BoardRelay.hosted(test.backgroundScope, source, dispatcher, ReplicaId("host-test"), clock, Random(0), config = config)
    }

    fun loom(name: String, scope: CoroutineScope = test.backgroundScope) = SpokeLoom(scope, name, Random(name.hashCode()), clock) { id, _ ->
        val (hubEnd, spokeEnd) = connectionPair()
        val hubSide = hubEnd.lagged()
        hubEnds += hubSide
        source.offer(hubSide)
        peerMesh(id, listOf(taps.getOrPut(name) { WireTap() }.wrap(spokeEnd.lagged())), dispatcher)
    }

    /** A board client of [author]'s through [loom], sending no payload over [maxPayloadBytes]. */
    fun client(
        author: ReplicaId,
        loom: SpokeLoom = loom(author.value),
        scope: CoroutineScope = test.backgroundScope,
        maxPayloadBytes: Int = BOARD_PAYLOAD_BYTES,
    ) = BoardClient(author, loom, Rendezvous.Existing(InMemoryTag("board")), scope, clock, config = config, maxPayloadBytes = maxPayloadBytes)

    /** This end, each frame it receives arriving [lag] after it was sent, in send order. */
    private fun Connection.lagged(): Connection = if (lag == Duration.ZERO) this else Lagged(this)

    private inner class Lagged(private val base: Connection) : Connection by base {
        override val incoming: Flow<ByteArray> = channelFlow {
            base.incoming.collect { frame ->
                launch {
                    delay(lag)
                    send(frame)
                }
            }
        }
    }

    companion object {
        /** The board's tuning under virtual time. */
        val CONFIG = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds, expectVirtualTime = true)
    }
}
