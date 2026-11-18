package us.tractat.bee.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.QuiltMessage

/**
 * [inner], handing [onFullState] the board in the first `FullState` a peer sends over it: on a
 * spoke, the relay's first-contact `FullState`, the whole board as the host holds it. [BoardClient]
 * is synced once its board [holds] that state ([BoardClient.synced]). It keeps nothing: once it has
 * handed the board on it decodes no more frames, so a connection carries no second copy of the
 * board for its lifetime.
 *
 * Workaround for tractat-us/kuilt#3142: a Quilter gives no signal that it has caught up with the
 * peer it joined, so bee reads the frame itself, as the Quilter will, in CBOR, the Quilter's
 * default format ([WireBudget] guesses its framing the same way). Each frame is decoded twice
 * until the first `FullState` arrives, and once from then on; [BoardClient] wraps a connection in
 * this only until it has synced. Remove it once the Quilter says it has caught up.
 */
internal class FirstContactSeam(private val inner: Seam, private val onFullState: (BoardState) -> Unit) : Seam by inner {
    /** Whether a `FullState` has been handed on. Only the Quilter's one collector of [incoming] touches it. */
    private var seen = false

    // Handed on before the Quilter reads the frame, so a board that already holds it is synced at once.
    override val incoming: Flow<Swatch> = inner.incoming.onEach { swatch ->
        if (!seen) {
            fullStateIn(swatch)?.let {
                seen = true
                onFullState(it)
            }
        }
    }

    /**
     * The board in [swatch], if it is a `FullState`. Anything that won't decode is the Quilter's to
     * drop, as it does (`runCatchingCancellable`): never the end of the connection.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fullStateIn(swatch: Swatch): BoardState? = try {
        (swatch.decode(Cbor, messages) as? QuiltMessage.FullState<BoardState>)?.state
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        val messages = QuiltMessage.serializer(BoardState.serializer())
    }
}

/**
 * Whether this board already holds everything in [other]: joining [other] changes nothing.
 *
 * It is what makes a client synced, rather than the relay's `FullState` merely having arrived: on
 * a multi-threaded dispatcher the Quilter can still be merging a large `FullState` into the board
 * when it is noted, and kuilt promises no order between the two (tractat-us/kuilt#3142).
 */
internal fun BoardState.holds(other: BoardState): Boolean = piece(other) == this
