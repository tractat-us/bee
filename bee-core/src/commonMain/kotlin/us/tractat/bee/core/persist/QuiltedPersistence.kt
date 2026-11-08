package us.tractat.bee.core.persist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [Quilted] state's snapshot in a [DurableStore], under [key]: [load] seeds a replica's
 * `initial` on start, and [saveOnChange] snapshots it as it changes. Invariant: **a snapshot only
 * grows.** Every write is the join of the last snapshot and the state, so a writer that started
 * from less than the store holds (a second tab that took over the writing) never shrinks it. The
 * one exception is a snapshot that doesn't decode: a writer replaces it, and [load] reports it.
 *
 * It assumes **one [Saver] per key**: two writers under one key, even in one process, race their
 * whole snapshots, and the later write wins. A browser's tabs take turns through a Web Lock.
 *
 * It names no application type, so it can move to kuilt (tractat-us/kuilt#3119): it is the one
 * `Quilter`↔`DurableStore` piece kuilt doesn't ship (spec §5.4, A5).
 *
 * The snapshot is the state's JSON under [serializer]. A deserialized state carries no local
 * author, so [load] hands it to the caller's `restore` to put one back before anything mutates it.
 *
 * @param interval the least time between two writes. A burst of changes is written as its last
 *   state, at most once per [interval].
 */
class QuiltedPersistence<S : Quilted<S>>(
    private val store: DurableStore,
    private val key: StoreKey,
    private val serializer: KSerializer<S>,
    private val interval: Duration = 2.seconds,
) {
    init {
        require(interval.isPositive()) { "interval must be positive, was $interval" }
    }

    /**
     * The saved state, passed through [restore], or null if nothing was ever saved under [key].
     *
     * @throws IllegalArgumentException if the snapshot doesn't decode (a [SerializationException],
     *   or a nested decoder's own refusal)
     */
    suspend fun load(restore: (S) -> S): S? = store.read(key)?.let(::decode)?.let(restore)

    /**
     * Snapshots [state] on [scope] until the returned [Saver] is stopped: its first value at once
     * unless the store already holds it, then each change at most once per [interval].
     * [prepare] takes out what must not be saved, such as presence; a change that [prepare] takes
     * out, or that leaves the snapshot as it was, is not written.
     */
    fun saveOnChange(state: StateFlow<S>, scope: CoroutineScope, prepare: (S) -> S = { it }): Saver =
        Saver(state, scope, prepare)

    private fun decode(bytes: ByteArray): S = FORMAT.decodeFromString(serializer, bytes.decodeToString())

    /**
     * One snapshot writer. **A store's failures are state; anything else is a bug.** A failed read
     * or write of the store lands in [lastFailure] and is retried after [interval]; the next
     * successful write clears it. A throwing `prepare`, or a state that doesn't encode, is never
     * retried: it fails this writer's coroutine on its scope, as any uncaught failure does.
     */
    inner class Saver internal constructor(
        private val state: StateFlow<S>,
        scope: CoroutineScope,
        private val prepare: (S) -> S,
    ) {
        private val _lastFailure = MutableStateFlow<Exception?>(null)

        /** The store's snapshot as this writer last read or wrote it; unread until the first save. */
        private var snapshot: Snapshot<S> = Snapshot.Unread

        /** What last went wrong reading or writing the store. Null once a write succeeds. */
        val lastFailure: StateFlow<Exception?> = _lastFailure.asStateFlow()

        // Last, so everything the loop reads is initialized before it can run.
        private val job: Job = scope.launch(CoroutineName("save-${key.name}")) {
            var saved: S? = null
            while (true) {
                val latest = state.first { it !== saved }
                saved = latest.takeIf { trySave(prepare(it)) }
                delay(interval)
            }
        }

        /**
         * Stops writing, then writes the state's last value if it isn't saved yet.
         *
         * @throws Exception what the store threw, if that last write failed, or what `prepare`
         *   or the encoding threw
         */
        suspend fun stop() {
            job.cancelAndJoin()
            try {
                withContext(NonCancellable) { save(prepare(state.value)) }
            } catch (failure: StoreFailure) {
                _lastFailure.value = failure.cause
                throw failure.cause
            }
            _lastFailure.value = null
        }

        /** Saves [prepared]; false, with the failure recorded, if the store failed. */
        private suspend fun trySave(prepared: S): Boolean = try {
            // A write is never abandoned midway: an abandoned write may still commit after a
            // later one (DurableStore.write), and an older snapshot would land over a newer one.
            withContext(NonCancellable) { save(prepared) }
            _lastFailure.value = null
            true
        } catch (failure: StoreFailure) {
            _lastFailure.value = failure.cause
            false
        }

        private suspend fun save(prepared: S) {
            val held = when (val known = snapshot) {
                Snapshot.Unread -> heldForWriting()
                is Snapshot.Read -> known.state
            }
            val next = held?.piece(prepared) ?: prepared
            if (next != held) {
                val bytes = FORMAT.encodeToString(serializer, next).encodeToByteArray()
                stored { store.write(key, bytes) }
            }
            snapshot = Snapshot.Read(next)
        }

        /** The store's snapshot, to grow. One that doesn't decode counts as none, to be replaced. */
        private suspend fun heldForWriting(): S? {
            val bytes = stored { store.read(key) } ?: return null
            return try {
                decode(bytes)
            } catch (unreadable: IllegalArgumentException) {
                // SerializationException is one; a nested decoder's refusal (a bad identity) may be another.
                null
            }
        }
    }

    /** The store's snapshot, as one writer knows it. */
    private sealed interface Snapshot<out T> {
        data object Unread : Snapshot<Nothing>

        /** Read or written: [state] is null when the store held nothing. */
        data class Read<T>(val state: T?) : Snapshot<T>
    }

    /** A failure of the store itself, as opposed to a bug in what is saved. */
    private class StoreFailure(override val cause: Exception) : Exception(cause)

    /** Runs [op] against the store, and marks what it throws as the store's own failure. */
    private inline fun <T> stored(op: () -> T): T = try {
        op()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw StoreFailure(failure)
    }

    private companion object {
        /** JSON, with kuilt's structured map keys (dots, version vectors) as key-value arrays. */
        val FORMAT = Json { allowStructuredMapKeys = true }
    }
}
