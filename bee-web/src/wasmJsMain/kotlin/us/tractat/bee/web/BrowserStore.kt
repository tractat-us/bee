package us.tractat.bee.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.persist.QuiltedPersistence
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.IndexedDbDurableStore
import us.tractat.kuilt.store.StoreKey

/**
 * This browser's copy of the board, in IndexedDB, so a closed tab loses nothing it did while the
 * host was down (spec §5.1, A7). Invariant: **every tab reads the copy, and only the tab holding
 * the Web Lock [LOCK] writes it.** Each tab is its own replica, so two writers would race whole
 * snapshots; the lock passes to a waiting tab when its holder closes. A tab that takes over joins
 * its board with the copy rather than replacing it ([QuiltedPersistence] only grows a snapshot),
 * so it never drops what the last writer saved and this tab missed.
 *
 * A copy belongs to one board ([BoardId]). The writer keeps one board's copy only: when it starts
 * keeping a different board, it deletes the old one, so a wiped or different host never takes in
 * an old board or its claim views.
 */
class BrowserStore private constructor(private val store: DurableStore) {
    /**
     * The board this tab keeps a copy of: [served], the one the host named, or when the host
     * didn't answer, the one this browser kept last. Null if neither is known.
     */
    suspend fun board(served: BoardId?): BoardId? = served ?: BoardId.keptIn(store)

    /**
     * [board]'s copy under this page's [author], or null if this browser keeps none of it.
     *
     * @throws kotlinx.serialization.SerializationException if the copy doesn't decode. The store
     *   is still good: the writer replaces the copy.
     */
    suspend fun load(board: BoardId, author: ReplicaId): BoardState? = copyOf(board).load { it.withReplica(author) }

    /**
     * Suspends until this tab holds [LOCK], then saves [state] as [board]'s copy on [scope],
     * without presence, for as long as the page lives: the lock is let go only when the page goes.
     */
    suspend fun keep(board: BoardId, state: StateFlow<BoardState>, scope: CoroutineScope): QuiltedPersistence<BoardState>.Saver {
        val granted = CompletableDeferred<Unit>()
        holdForever(LOCK) { granted.complete(Unit) }
        granted.await()
        val kept = BoardId.keptIn(store)
        if (kept != board) {
            kept?.let { store.delete(it.snapshotKey) }
            BoardId.recordIn(store, board)
        }
        return copyOf(board).saveOnChange(state, scope, BoardState::withoutPresence)
    }

    private fun copyOf(board: BoardId) = QuiltedPersistence(store, board.snapshotKey, BoardState.serializer())

    companion object {
        /** The IndexedDB database the board is kept in. */
        const val DATABASE: String = "bee-board"

        /** The Web Lock whose holder is the one tab that writes the board (A7). */
        const val LOCK: String = "bee-board-store"

        /**
         * Opens this browser's copy.
         *
         * @throws IllegalStateException if this page can't take the Web Lock (an insecure origin,
         *   which has no `navigator.locks`): a tab that could never write would only ever read
         *   stale copies.
         * @throws Exception if IndexedDB won't open
         */
        suspend fun open(): BrowserStore {
            check(webLocksAvailable()) { "this page has no Web Locks (an insecure origin?), so it can't keep the board" }
            val store = mappingJsThrows { IndexedDbDurableStore.open(DATABASE) }
            return BrowserStore(store.mappingJsThrows())
        }
    }
}

/**
 * This store, with every JS throw that escapes it rethrown as a [JsStoreException]. On Kotlin/Wasm
 * a `JsException` is a `Throwable`, not an `Exception`, so without this a failed IndexedDB call
 * passes every `catch (Exception)` and stops the page. Remove once tractat-us/kuilt#3143 makes
 * `IndexedDbDurableStore` wrap its synchronous throws itself.
 */
internal fun DurableStore.mappingJsThrows(): DurableStore = JsMappingStore(this)

private class JsMappingStore(private val store: DurableStore) : DurableStore {
    override suspend fun read(key: StoreKey): ByteArray? = mappingJsThrows { store.read(key) }

    override suspend fun write(key: StoreKey, bytes: ByteArray) = mappingJsThrows { store.write(key, bytes) }

    override suspend fun delete(key: StoreKey) = mappingJsThrows { store.delete(key) }
}

/** A JS error thrown out of a [DurableStore], as an [Exception] (tractat-us/kuilt#3143). */
internal class JsStoreException(cause: JsException) : Exception("IndexedDB failed: ${cause.message}", cause)

private inline fun <T> mappingJsThrows(op: () -> T): T = try {
    op()
} catch (thrown: JsException) {
    throw JsStoreException(thrown)
}

/** Whether this page can take a Web Lock: `navigator.locks` exists only in secure contexts. */
@JsFun("() => typeof navigator !== 'undefined' && !!navigator.locks")
private external fun webLocksAvailable(): Boolean

/**
 * Asks for the exclusive Web Lock [name] and calls [onGranted] once this page holds it. The
 * request's promise never settles, so the lock is held until the page goes.
 */
@JsFun("(name, onGranted) => { navigator.locks.request(name, () => new Promise(() => onGranted())); }")
private external fun holdForever(name: String, onGranted: () -> Unit)
