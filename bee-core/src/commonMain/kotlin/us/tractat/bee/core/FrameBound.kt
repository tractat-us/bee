package us.tractat.bee.core

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * The largest WebSocket frame a board link carries, in bytes, at both ends: the host reads no
 * bigger (bee-host's WebSocket install closes the socket with 1009, too big, on a frame header that
 * announces more, before Ktor allocates a buffer for it), and a [BoardClient] sends no bigger
 * ([BOARD_PAYLOAD_BYTES]). Ktor's own default is `Long.MAX_VALUE`, so a header alone could make the
 * host allocate up to 2 GiB.
 *
 * The largest legitimate frame is a board `FullState`, sent once per spoke connection. Nothing
 * measures one yet; a board of text surfaces and their action records is kilobytes to a few MiB,
 * so 64 MiB leaves an order of magnitude of headroom. Only a loopback or keyed device reaches a
 * WebSocket at all, so on the host this is defence in depth, not the key gate.
 */
const val MAX_FRAME_BYTES: Long = 64L * 1024 * 1024

/**
 * What the board link's layers under the Quilter may add to one of its payloads, in bytes: a
 * gossip frame's header and origin id, and the mesh frame's type byte. Each is tens of bytes; this
 * is generous, so a change of peer id length can't push a payload that fit over the frame bound.
 */
const val FRAME_ENVELOPE_BYTES: Int = 4 * 1024

/**
 * The largest Quilter payload a [BoardClient] sends: [MAX_FRAME_BYTES] less [FRAME_ENVELOPE_BYTES],
 * so the frame it rides in is never over the host's bound.
 */
const val BOARD_PAYLOAD_BYTES: Int = MAX_FRAME_BYTES.toInt() - FRAME_ENVELOPE_BYTES

/**
 * [inner], refusing any payload over [limit], or over [inner]'s own budget where that is tighter,
 * before it reaches the wire. [refused] completes with the first refusal, so the client can say
 * why it can't sync and stop, rather than send a frame the host would close the socket over.
 *
 * As kuilt's [Seam] contract asks, an addressed [sendTo] throws [PayloadTooLarge] and a
 * [broadcast] drops. The Quilter swallows either at debug level, so [refused] is the only notice.
 *
 * Workaround for tractat-us/kuilt#3144 (the Quilter drops an over-budget `Delta` or `FullState`
 * without a word) and tractat-us/kuilt#3145 (kuilt's WebSocket installs take no frame bound, so the
 * board link publishes no budget of its own). Remove it once the Quilter reports an over-budget
 * frame and the link publishes the host's bound.
 */
internal class BoundedSeam(private val inner: Seam, private val limit: Int) : Seam by inner {
    val refused: CompletableDeferred<BoardTooLarge> = CompletableDeferred()

    override val maxPayloadBytes: Int get() = inner.maxPayloadBytes?.let { minOf(it, limit) } ?: limit

    override suspend fun broadcast(payload: ByteArray) {
        if (fits(payload)) inner.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        val budget = maxPayloadBytes
        if (!fits(payload, budget)) throw PayloadTooLarge(payload.size, budget, reservedBytes = 0)
        inner.sendTo(peer, payload)
    }

    private fun fits(payload: ByteArray, budget: Int = maxPayloadBytes): Boolean {
        if (payload.size <= budget) return true
        refused.complete(BoardTooLarge(payload.size, budget))
        return false
    }
}
