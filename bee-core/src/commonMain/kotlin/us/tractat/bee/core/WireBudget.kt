package us.tractat.bee.core

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage

/**
 * A board write refused because its delta wouldn't fit the board link (Review Focus 5). Nothing
 * was written, and the board carries on: the next write that fits goes through.
 *
 * A seam's `broadcast` drops a payload over its `maxPayloadBytes` without a word, so a delta that
 * big would sit on the writer's replica and reach nobody. [BoardClient] measures each delta
 * before it lands and throws this instead.
 *
 * It is an [IllegalArgumentException], the type a board mutator throws for a write it refuses,
 * so the bridge's tools already answer it as a tool error that carries this message.
 *
 * @property bytes the delta's size as a frame on the board link
 * @property limit the link's payload budget, in bytes
 */
class BoardWriteTooLarge(val bytes: Int, val limit: Int) :
    IllegalArgumentException("The write is $bytes bytes on the wire, over the board link's limit of $limit bytes, so nothing was written.")

/**
 * What a board's frames weigh as the Quilter sends them: a `QuiltMessage.Delta` or
 * `QuiltMessage.FullState` in CBOR, the Quilter's default format. The sequence number or
 * high-water is the largest there is, so a measure never falls short of the frame the Quilter
 * sends. This is the one place bee sizes the wire.
 *
 * A workaround for tractat-us/kuilt#3144: the Quilter drops a `Delta` or `FullState` over its
 * seam's budget at debug level, so bee weighs its frames itself, guessing the Quilter's framing.
 * [BoardClient] weighs each write's delta before it lands, and refuses one that won't fit. Offline
 * writes that each fit can still add up past the bound in the next connection's `FullState`, and
 * so can the board a joiner is sent on first contact: [BoundedSeam] refuses that `FullState`, the
 * client reports it as [BoardClient.tooLarge], and it dials again only once [fullStateBytes] says
 * the board could fit. bee doesn't chunk a `FullState`; that belongs to the Quilter (kuilt#3144).
 */
internal object WireBudget {
    private val messages = QuiltMessage.serializer(BoardState.serializer())

    fun deltaBytes(sender: ReplicaId, delta: BoardState): Int =
        Cbor.encodeToByteArray(messages, QuiltMessage.Delta(sender, Long.MAX_VALUE, delta)).size

    /**
     * What a `FullState` of [board] weighs as a payload from one of [author]'s connections, at the
     * most: under the longest connection replica id [BoardClient] mints, `<author>#c<n>`.
     */
    fun fullStateBytes(author: ReplicaId, board: BoardState): Int {
        val sender = ReplicaId("${author.value}#c${Int.MAX_VALUE}")
        return Cbor.encodeToByteArray(messages, QuiltMessage.FullState(sender, board, upThrough = Long.MAX_VALUE)).size
    }

    /** Throws [BoardWriteTooLarge] if [delta] from [sender] won't fit [limit]. */
    fun check(sender: ReplicaId, delta: BoardState, limit: Int) {
        val bytes = deltaBytes(sender, delta)
        if (bytes > limit) throw BoardWriteTooLarge(bytes, limit)
    }
}
