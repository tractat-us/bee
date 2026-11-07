package us.tractat.bee.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.MonotonicMillis
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// bee-core has no logging dependency (kotlin-logging is bee-host's), and Task 3 may not add one,
// so the redial loop and the closes below fail quietly. Each comment says what's dropped.

/**
 * The board's replication tuning, as kuilt's Patchwork relay: a 1 s anti-entropy backstop behind
 * the gossip flood and the merge echo, and a spoke that stops acking is evicted after 30 s, so a
 * reconnect's stale fabric id doesn't pin the pending-delta buffer for kuilt's default 5 minutes.
 */
val BEE_QUILTER_CONFIG: QuilterConfig = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds)

/** Marks [BoardClient]'s lifecycle test hooks. They aren't an API. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "BoardClient's lifecycle test hooks aren't an API.")
@Retention(AnnotationRetention.BINARY)
annotation class BoardClientTestHook

/**
 * One peer's long-lived replica of the board. Invariant: **the client holds the board, and a
 * connection only replicates it.**
 *
 * This is reconnect shape (A) from #2869's M2 (spec §5.1, A4), kuilt's Patchwork shape. While
 * [connect]ed, the client keeps one connection to [rendezvous] at a time, redialling [redialAfter]
 * after a failed dial or a tear until [disconnect]. Each connection weaves a fresh seam from
 * [loom] and builds a fresh [Quilter] over it, seeded with the held board, on a child job of
 * [scope]. When it ends, its job is cancelled and the held board keeps its last state. Offline,
 * [mutate] writes straight onto the held board, and the next connection's first-contact
 * `FullState` carries the edit, under the dot it was minted with, never re-applied.
 *
 * **Identities.** Every edit names [author] through the transform (`it.openSurface(author, …)`):
 * it is the stable CRDT author for this process, online or offline. The Quilter's own replica id
 * is fresh per connection (`<author>#c<n>`), because a rebuilt Quilter restarts its delta seqs at
 * 1 and peers key them by that id. The fabric peer id is fresh per weave too ([SpokeLoom]), and it
 * is what [link] shows.
 *
 * **The mirror is identity-guarded.** The pump that copies a connection's state into the held
 * board writes only while that connection is still the live one, checked under [mutex], and reads
 * the connection's current state rather than the emission. A late emission from a parted Quilter
 * can therefore never overwrite an offline edit made after the part.
 *
 * **Locking.** The held board, the live connection, [online] and [link] change together under one
 * [Mutex], which is never held across a suspension: weaving and closing happen outside it.
 *
 * @param clock the Quilter's eviction clock, read in milliseconds. Tests pass virtual time.
 */
@Suppress("LongParameterList")
class BoardClient(
    private val author: ReplicaId,
    private val loom: Loom,
    private val rendezvous: Rendezvous,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    initial: BoardState = BoardState.EMPTY,
    private val config: QuilterConfig = BEE_QUILTER_CONFIG,
    private val redialAfter: Duration = 1.seconds,
) {
    /** One connection: its seam, its Quilter, and the job they and their pumps run under. */
    class Connection internal constructor(
        internal val seam: Seam,
        internal val quilter: Quilter<BoardState>,
        internal val job: Job,
        internal val scope: CoroutineScope,
    ) {
        /** Completes when the connection is over: its seam tore, or its Quilter was found closed. */
        internal val ended = CompletableDeferred<Unit>()
    }

    private val mutex = Mutex()
    private val held = MutableStateFlow(initial)
    private val _online = MutableStateFlow(false)
    private val _link = MutableStateFlow<String?>(null)
    private val wanted = MutableStateFlow(false)
    private var live: Connection? = null
    private var connections = 0

    /** The board as this peer holds it, online or not. */
    val state: StateFlow<BoardState> = held.asStateFlow()

    /** Whether a connection to the relay is live. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** The live connection's fabric peer id, or null offline. Presence names the link by it. */
    val link: StateFlow<String?> = _link.asStateFlow()

    private val driver: Job = scope.launch(CoroutineName("board-client-${author.value}")) {
        wanted.collectLatest { if (it) stayConnected() }
    }

    /** Goes online and stays online, redialling after a tear, until [disconnect]. */
    fun connect() {
        wanted.value = true
    }

    /**
     * Goes offline and keeps the held board. It takes effect on [scope]: [online] turns false once
     * the connection has parted. A no-op when already offline.
     */
    fun disconnect() {
        wanted.value = false
    }

    /** Stops for good: parts the live connection and stops the redial loop. */
    suspend fun close() {
        wanted.value = false
        driver.cancelAndJoin()
    }

    /** Applies [f]'s patch to the board, online or offline. Never waits on the network. */
    suspend fun mutate(f: (BoardState) -> Patch<BoardState>) {
        mutateOrSkip(f)
    }

    /**
     * [mutate], for a transform that may decline by returning null. Returns whether it wrote. The
     * decision is made against the very state the patch lands on.
     */
    suspend fun mutateOrSkip(f: (BoardState) -> Patch<BoardState>?): Boolean = mutex.withLock {
        val connection = live
        if (connection != null && connection.seam.state.value !is SeamState.Torn) {
            val wrote = writeOnline(connection, f)
            if (wrote != null) {
                held.value = connection.quilter.state.value
                return@withLock wrote
            }
        }
        // Offline, or the live Quilter closed itself before its tear reached the driver: the edit
        // goes onto the held board, and the next connection's FullState carries it.
        connection?.let { partLocked(it) }
        val patch = f(held.value) ?: return@withLock false
        held.value = held.value.piece(patch.delta)
        true
    }

    /** The result of [f] through [connection]'s Quilter, or null if that Quilter has closed. */
    private fun writeOnline(connection: Connection, f: (BoardState) -> Patch<BoardState>?): Boolean? {
        var ran = false
        return try {
            connection.quilter.mutateOrSkip { ran = true; f(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: IllegalStateException) {
            // mutateOrSkip checks for a closed Quilter before it runs the transform, so a throw
            // before [f] ran is that check; anything [f] throws is the caller's.
            if (ran) throw closed
            null
        }
    }

    private suspend fun stayConnected() {
        while (true) {
            val connection = openOrNull()
            if (connection != null) {
                try {
                    connection.ended.await()
                } finally {
                    withContext(NonCancellable) { end(connection) }
                }
            }
            delay(redialAfter)
        }
    }

    private suspend fun openOrNull(): Connection? {
        val seam = try {
            loom.weave(rendezvous) // suspends: outside the lock
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Dropped: the dial failure itself. The client stays offline and redials.
            return null
        }
        val connection = try {
            mutex.withLock { wire(seam).also { publish(it) } }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { closeQuietly(seam) }
            throw failure
        }
        connection.quilter.state.collectIn(connection, "mirror") { mirrorIfLive(connection) }
        connection.seam.state.collectIn(connection, "tear") { if (it is SeamState.Torn) connection.ended.complete(Unit) }
        return connection
    }

    /** A fresh Quilter over [seam], seeded with the held board, on a new child job. Holds [mutex]. */
    private fun wire(seam: Seam): Connection {
        val n = ++connections
        val job = SupervisorJob(scope.coroutineContext[Job])
        val connectionScope = scope + job
        try {
            val quilter = Quilter(
                replica = ReplicaId("${author.value}#c$n"),
                seam = seam,
                initial = held.value,
                messageSerializer = QuiltMessage.serializer(BoardState.serializer()),
                scope = connectionScope,
                config = config,
                clock = MonotonicMillis { clock().toEpochMilliseconds() },
            )
            return Connection(seam, quilter, job, connectionScope)
        } catch (failure: Throwable) {
            job.cancel()
            throw failure
        }
    }

    /** Makes [connection] live. Holds [mutex]. */
    private fun publish(connection: Connection) {
        check(live == null) { "${author.value} is already connected" }
        live = connection
        held.value = connection.quilter.state.value
        _link.value = connection.seam.selfId.value
        _online.value = true
    }

    /**
     * Copies [connection]'s state into the held board, **only while it is still the live
     * connection**. Public for the guard's test, which drives a late emission directly.
     */
    @BoardClientTestHook
    suspend fun mirror(connection: Connection) = mirrorIfLive(connection)

    private suspend fun mirrorIfLive(connection: Connection) {
        mutex.withLock { if (live === connection) held.value = connection.quilter.state.value }
    }

    /** The live connection, for the guard's test. */
    @BoardClientTestHook
    suspend fun liveConnection(): Connection? = mutex.withLock { live }

    /** Ends [connection]: parts it if it's still live, then stops its job and closes its seam. */
    private suspend fun end(connection: Connection) {
        mutex.withLock { partLocked(connection) }
        connection.job.cancelAndJoin()
        connection.quilter.close()
        closeQuietly(connection.seam)
    }

    /**
     * Makes [connection] no longer live, keeping its last state, and cancels its job, so its
     * mirror can't run again. Holds [mutex].
     */
    private fun partLocked(connection: Connection) {
        connection.ended.complete(Unit)
        if (live !== connection) return
        live = null
        held.value = connection.quilter.state.value
        _online.value = false
        _link.value = null
        connection.job.cancel()
    }

    private suspend fun closeQuietly(seam: Seam) {
        try {
            seam.close() // suspends: always outside the lock
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Dropped: a failure closing a seam that is already parted. Nothing holds it.
        }
    }

    // Dropped: a pump body's failure. Neither body throws anything but cancellation.
    private fun <T> StateFlow<T>.collectIn(connection: Connection, name: String, body: suspend (T) -> Unit) {
        pumpIn(connection.scope, onFailure = { _, _ -> PumpVerdict.CONTINUE }, name = "${connection.quilter.replica.value}-$name", body = body)
    }
}
