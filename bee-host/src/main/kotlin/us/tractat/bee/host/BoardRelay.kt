package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.bee.core.BEE_QUILTER_CONFIG
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The host's board relay and its always-on replica. Invariant: **every change to the host's
 * board reaches every spoke within a round trip**, including edits a spoke made offline.
 *
 * This follows kuilt's demo `PatchworkRelay`:
 *
 * - **Fabric.** [hosted] bonds every accepted connection into one star seam with
 *   `hostedOverlay`, a full-fanout `GossipSeam` hub that floods each spoke's broadcast to every
 *   other spoke. `BeeHost` accepts them with a `KtorConnectionSource` at [PATH]. The primary
 *   constructor takes any hub [Seam], so tests drive the relay in memory.
 * - **The host replica.** A [Quilter] on the hub seam holds the board, so a late joiner, or a
 *   spoke back from offline, gets the whole board in its first-contact `FullState`.
 * - **The forward.** Every frame that changes the host's board is re-broadcast by the host's own
 *   replica as it arrived: a spoke's delta as that delta, a spoke's `FullState` as its state.
 *   The flood alone is not enough, for two reasons. A reconnecting spoke's offline edits reach
 *   the hub point to point, in its `FullState`, which the hub does not flood. And a spoke that
 *   joined while another was mid-stream expects that spoke's deltas from seq 1: it buffers the
 *   ones that arrive, and its Resend can't reach a spoke that isn't its neighbour, so without the
 *   forward it would see each edit only at the next anti-entropy round. The host's own delta
 *   stream is one every spoke can follow, since the hub's first-contact `FullState` syncs a
 *   spoke's cursor for it. A frame that leaves the host's board unchanged, a duplicate or a
 *   stale `FullState`, is not forwarded. Before #24 the relay re-broadcast its whole merged
 *   board on every change instead, so a 947 B keystroke came down as a 23 KB frame.
 *
 * @param replica the host's CRDT author and Quilter id, fresh per boot (`host-<uuid>`): a rebooted
 *   host restarting its delta seqs under an old id would look stale to the spokes. The Quilter
 *   keeps kuilt's monotonic clock for eviction.
 */
class BoardRelay(
    hub: Seam,
    scope: CoroutineScope,
    val replica: ReplicaId,
    initial: BoardState = BoardState.EMPTY,
    config: QuilterConfig = BEE_QUILTER_CONFIG,
) : AutoCloseable {
    private val messageSerializer = QuiltMessage.serializer(BoardState.serializer())

    private val tap = ForwardingSeam(hub)
    private val quilter: Quilter<BoardState> = Quilter(
        replica = replica,
        seam = tap,
        initial = initial,
        messageSerializer = messageSerializer,
        scope = scope,
        config = config,
        binaryFormat = FORMAT,
    )

    /** The host's merged board. */
    val state: StateFlow<BoardState> = quilter.state

    init {
        // The Quilter may start reading on another thread before it is assigned, so the forward is
        // switched on only now. A frame read before this is merged but not forwarded; anti-entropy
        // carries it to the other spokes.
        tap.onFrame = ::forward
    }

    /** Applies [f]'s patch to the host's board and broadcasts it. [f] names [replica] as author. */
    fun mutate(f: (BoardState) -> Patch<BoardState>) = quilter.mutate(f)

    override fun close() = quilter.close()

    /**
     * Re-broadcasts what [frame] would change on the host's board, under the host's replica. It
     * runs on the Quilter's inbound collector, just before the Quilter dispatches the same frame,
     * so the board it compares against is the one the frame lands on.
     */
    private fun forward(frame: Swatch) {
        val change = when (val message = decodeOrNull(frame)) {
            is QuiltMessage.Delta -> message.delta
            is QuiltMessage.FullState -> message.state
            else -> return
        }
        try {
            quilter.mutateOrSkip { board -> Patch(change).takeIf { board.piece(change) != board } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: IllegalStateException) {
            logger.debug { "${replica.value}: forward skipped, the Quilter has closed: $closed" }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // A throw here would end the Quilter's inbound collector, and with it the host replica.
            // The frame still merges; anti-entropy carries it on.
            logger.warn(failure) { "${replica.value}: forward failed" }
        }
    }

    /** [frame] as a Quilt message, or null if it is not one; the Quilter logs and drops those itself. */
    private fun decodeOrNull(frame: Swatch): QuiltMessage<BoardState>? = try {
        frame.decode(FORMAT, messageSerializer)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") undecodable: Exception) {
        logger.debug { "${replica.value}: not forwarding an undecodable frame: $undecodable" }
        null
    }

    /** [base], with every inbound frame shown to [onFrame] before the reader sees it. */
    private class ForwardingSeam(private val base: Seam) : Seam by base {
        @Volatile var onFrame: (Swatch) -> Unit = {}

        override val incoming: Flow<Swatch> = base.incoming.onEach { onFrame(it) }
    }

    companion object {
        /** The board relay's WebSocket route. */
        const val PATH: String = "/ws/board"

        /** The Quilter's wire format, named here because the forward decodes the same frames. */
        @OptIn(ExperimentalSerializationApi::class)
        private val FORMAT: BinaryFormat = Cbor

        /** A relay whose hub accepts spokes from [source]. Background work runs on [scope]. */
        suspend fun hosted(
            scope: CoroutineScope,
            source: ConnectionSource,
            dispatcher: CoroutineContext,
            replica: ReplicaId,
            clock: () -> Instant,
            random: Random = Random.Default,
            initial: BoardState = BoardState.EMPTY,
            config: QuilterConfig = BEE_QUILTER_CONFIG,
        ): BoardRelay {
            val hub = scope.hostedOverlay(PeerId(replica.value), source, dispatcher, random, clock)
            return BoardRelay(hub, scope, replica, initial, config)
        }
    }
}
