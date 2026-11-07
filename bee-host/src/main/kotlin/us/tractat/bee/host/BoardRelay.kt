package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import us.tractat.bee.core.BEE_QUILTER_CONFIG
import us.tractat.bee.core.BoardState
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PumpFailure
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.quilter.MonotonicMillis
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
 * - **The merge echo.** A reconnecting spoke's offline edits reach the hub point to point, in its
 *   `FullState`, which the hub does not flood. So every change to the host's board is
 *   re-broadcast as a delta of its own, and the other spokes see the edit within a round trip
 *   instead of at the next anti-entropy round. Joining a board into itself changes nothing, and
 *   [BoardState] equality is structural, so the echo's own write leaves the state equal and
 *   the loop stops. The cost: each echo is the whole board.
 *
 * @param replica the host's CRDT author and Quilter id, fresh per boot (`host-<uuid>`): a rebooted
 *   host restarting its delta seqs under an old id would look stale to the spokes.
 * @param clock the Quilter's eviction clock.
 */
class BoardRelay(
    hub: Seam,
    scope: CoroutineScope,
    val replica: ReplicaId,
    clock: () -> Instant,
    initial: BoardState = BoardState.EMPTY,
    config: QuilterConfig = BEE_QUILTER_CONFIG,
) : AutoCloseable {
    private val quilter = Quilter(
        replica = replica,
        seam = hub,
        initial = initial,
        messageSerializer = QuiltMessage.serializer(BoardState.serializer()),
        scope = scope,
        config = config,
        clock = MonotonicMillis { clock().toEpochMilliseconds() },
    )

    /** The host's merged board. */
    val state: StateFlow<BoardState> = quilter.state

    /** Everyone connected through the hub, the host included. */
    val peers: StateFlow<Set<PeerId>> = hub.peers

    init {
        var echoed: BoardState? = null
        state.pumpIn(scope, onFailure = ::report, name = "${replica.value}-merge-echo") { merged ->
            if (merged == echoed) return@pumpIn
            echoed = merged
            echo()
        }
    }

    /** Applies [f]'s patch to the host's board and broadcasts it. [f] names [replica] as author. */
    fun mutate(f: (BoardState) -> Patch<BoardState>) = quilter.mutate(f)

    override fun close() = quilter.close()

    private fun echo() {
        try {
            quilter.mutate { merged -> Patch(merged) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: IllegalStateException) {
            logger.debug { "${replica.value}: echo skipped, the Quilter has closed: $closed" }
        }
    }

    private fun report(phase: PumpFailure, failure: Throwable): PumpVerdict {
        logger.warn(failure) { "${replica.value}: merge echo failed ($phase)" }
        return PumpVerdict.CONTINUE
    }

    companion object {
        /** The board relay's WebSocket route. */
        const val PATH: String = "/ws/board"

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
            return BoardRelay(hub, scope, replica, clock, initial, config)
        }
    }
}
