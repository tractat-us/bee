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
 * What a board delta weighs as the Quilter frames it: a `QuiltMessage.Delta` in CBOR, the
 * Quilter's default format. The sequence number is the largest there is, so the measure never
 * falls short of the frame the Quilter sends.
 *
 * A workaround for tractat-us/kuilt#3144: the Quilter drops a `Delta` or `FullState` over its
 * seam's budget at debug level, so bee weighs each write itself, guessing the Quilter's framing.
 * Only a delta is weighed. An online write's delta goes on the wire as it is, so it is covered.
 * An offline write is weighed alone against the last link's budget, but it reaches the wire inside
 * the next connection's `FullState`, which nobody weighs: offline writes that each fit can still
 * add up past the budget, and so can the board a joiner is sent on first contact. bee doesn't
 * chunk a `FullState`; that belongs to the Quilter (kuilt#3144).
 */
internal object WireBudget {
    private val messages = QuiltMessage.serializer(BoardState.serializer())

    fun deltaBytes(sender: ReplicaId, delta: BoardState): Int =
        Cbor.encodeToByteArray(messages, QuiltMessage.Delta(sender, Long.MAX_VALUE, delta)).size

    /** Throws [BoardWriteTooLarge] if [delta] from [sender] won't fit [limit]; a null limit fits anything. */
    fun check(sender: ReplicaId, delta: BoardState, limit: Int?) {
        if (limit == null) return
        val bytes = deltaBytes(sender, delta)
        if (bytes > limit) throw BoardWriteTooLarge(bytes, limit)
    }
}
