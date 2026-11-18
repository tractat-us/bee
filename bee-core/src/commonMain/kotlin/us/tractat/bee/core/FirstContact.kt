package us.tractat.bee.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.quilter.QuiltMessage

/**
 * [inner], noting the board in the first `FullState` a peer sends over it: on a spoke, the
 * relay's first-contact `FullState`, the whole board as the host holds it. [BoardClient] is synced
 * once its board has joined that state ([BoardClient.synced]).
 *
 * Workaround for tractat-us/kuilt#3142: a Quilter gives no signal that it has caught up with the
 * peer it joined, so bee reads the frame itself, as the Quilter will, in CBOR, the Quilter's
 * default format ([WireBudget] guesses its framing the same way). Each frame is decoded twice
 * until the first `FullState` arrives, and once from then on. Remove it once the Quilter says it
 * has caught up.
 */
internal class FirstContactSeam(private val inner: Seam) : Seam by inner {
    private val _relayBoard = MutableStateFlow<BoardState?>(null)

    /** The board in the first `FullState` a peer sent, or null before one arrives. */
    val relayBoard: StateFlow<BoardState?> = _relayBoard.asStateFlow()

    // Noted before the Quilter reads the frame, so a board that already holds it is synced at once.
    override val incoming: Flow<Swatch> = inner.incoming.onEach { swatch ->
        if (_relayBoard.value == null) fullStateIn(swatch)?.let { _relayBoard.value = it }
    }

    private fun fullStateIn(swatch: Swatch): BoardState? = try {
        (swatch.decode(Cbor, messages) as? QuiltMessage.FullState<BoardState>)?.state
    } catch (_: SerializationException) {
        // A frame that isn't a board message: the Quilter drops it too.
        null
    }

    private companion object {
        val messages = QuiltMessage.serializer(BoardState.serializer())
    }
}
