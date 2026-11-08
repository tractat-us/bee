package us.tractat.bee.core

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Which board a replica or a copy belongs to.
 *
 * **One board is one board snapshot and one claims log together.** The host records the id in
 * both its stores, `board/` and `claims/`, and on boot keeps it only if both record the same one.
 * If either records none, or they disagree, the host mints a new board. So a wiped host, and a
 * host whose `claims/` was moved aside, is a new board. The second matters as much as the first:
 * the board mirrors claim views timestamped by log index, and a new log restarting at index 1
 * can't outrank an old log's views until it passes them, so an old snapshot must never meet a new
 * log.
 *
 * **Every socket names its board.** A board or claims socket carries the id it expects as
 * `?`[PARAM]`=<id>`, and the host refuses any other, or none, before the upgrade. A replica's
 * first frame is its whole state, so a check after the upgrade would be too late: a tab left open
 * across a wipe would already have pushed its old board, claim views and all, into the new host
 * (bee#46). A page or bridge pins the id its replica was built for ([BoardPin]) and never dials
 * with another.
 *
 * A copy is kept under its board's id ([snapshotKey]), so a browser reloading against a new host
 * starts from that board's copy, not an old board's. The id travels to a page as the plain-text
 * body of `GET` [PATH].
 *
 * An id is URL-safe (letters, digits, `-`, `.`, `_` and `~`), so it goes into a query as it is.
 */
@JvmInline
value class BoardId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it in URL_SAFE }) { "Not a board id: \"$value\"" }
    }

    /** Where a copy of this board is kept. `.v1` names the snapshot's format. */
    val snapshotKey: StoreKey get() = StoreKey("board/$value/state.v1")

    override fun toString(): String = value

    companion object {
        /** The host's route that names its board, as text. */
        const val PATH: String = "/board-id"

        /** The query parameter a board or claims socket names its board in. */
        const val PARAM: String = "board"

        /** Where a store records which board it keeps a copy of. */
        val KEY: StoreKey = StoreKey("board/id")

        private val URL_SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

        /** A new board's id, unlike any other. */
        @OptIn(ExperimentalUuidApi::class)
        fun mint(): BoardId = BoardId("board-${Uuid.random()}")

        /** The board [store] keeps a copy of, or null if it keeps none. */
        suspend fun keptIn(store: DurableStore): BoardId? = store.read(KEY)?.decodeToString()?.let(::BoardId)

        /** Records in [store] that it keeps [board]'s copy. */
        suspend fun recordIn(store: DurableStore, board: BoardId) = store.write(KEY, board.value.encodeToByteArray())
    }
}
