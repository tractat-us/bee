package us.tractat.bee.core

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Which board a copy belongs to. The host mints one at its first boot and keeps it beside its
 * snapshot, so a wiped or different host has a different id. A copy is kept under its board's
 * id ([snapshotKey]). A page checks the id once, at load: a browser reloading against a new host
 * starts from that board's copy, not an old board's. A tab left open across a wipe is not
 * covered, and keeps feeding its old board and claim views to the new host (bee#46).
 *
 * The id travels to a page as the plain-text body of `GET` [PATH]: nothing else on the wire
 * changes.
 */
@JvmInline
value class BoardId(val value: String) {
    init {
        require(value.isNotBlank() && value.none { it == '/' || it.isWhitespace() }) { "Not a board id: \"$value\"" }
    }

    /** Where a copy of this board is kept. `.v1` names the snapshot's format. */
    val snapshotKey: StoreKey get() = StoreKey("board/$value/state.v1")

    override fun toString(): String = value

    companion object {
        /** The host's route that names its board, as text. */
        const val PATH: String = "/board-id"

        /** Where a store records which board it keeps a copy of. */
        val KEY: StoreKey = StoreKey("board/id")

        /** A new board's id, unlike any other. */
        @OptIn(ExperimentalUuidApi::class)
        fun mint(): BoardId = BoardId("board-${Uuid.random()}")

        /** The board [store] keeps a copy of, or null if it keeps none. */
        suspend fun keptIn(store: DurableStore): BoardId? = store.read(KEY)?.decodeToString()?.let(::BoardId)

        /** Records in [store] that it keeps [board]'s copy. */
        suspend fun recordIn(store: DurableStore, board: BoardId) = store.write(KEY, board.value.encodeToByteArray())
    }
}
