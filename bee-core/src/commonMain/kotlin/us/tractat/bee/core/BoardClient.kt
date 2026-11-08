package us.tractat.bee.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.core.util.ExponentialBackoff
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The board's replication tuning, as kuilt's Patchwork relay: a 1 s anti-entropy backstop behind
 * the gossip flood and the relay's forward, and a spoke that stops acking is evicted after 30 s,
 * so a reconnect's stale fabric id doesn't pin the pending-delta buffer for kuilt's default 5
 * minutes.
 */
val BEE_QUILTER_CONFIG: QuilterConfig = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds)

/**
 * One peer's long-lived replica of the board. Invariant: **the client holds the board, and a
 * connection only replicates it.**
 *
 * This is reconnect shape (A) from #2869's M2 (spec §5.1, A4), kuilt's Patchwork shape. While
 * [connect]ed, the client keeps one connection to [rendezvous] at a time, redialling after a failed
 * dial or a tear (full-jitter exponential backoff, 250 ms to 5 s) until [disconnect]. Each
 * connection weaves a fresh seam from [loom] and builds a fresh [Quilter] over it, seeded with the
 * held board, on a child job of [scope]. When it ends, its job is cancelled and the held board
 * keeps its last state. Offline, [mutate] writes straight onto the held board, and the next
 * connection's first-contact `FullState` carries the edit, under the dot it was minted with, never
 * re-applied.
 *
 * **The backoff resets only for a connection that settled**: one still live [settleAfter] after
 * it went live. A dial that succeeds says nothing about whether the relay will keep the link, so a
 * relay that hangs up on every spoke at once (for a frame it refuses, or any reason the client
 * can't see) meets a backoff that grows to its cap, never a redial every few hundred milliseconds
 * (bee#65).
 *
 * **A board too large to sync stops, and says so** (bee#65). No payload over [maxPayloadBytes]
 * reaches the wire ([BoundedSeam]). A write's delta that big is refused before it lands (below),
 * so what the seam refuses is a `FullState`: the first one refused ends its connection, lands in
 * [tooLarge] and [lastFailure], and is logged. The client then dials again only once the held
 * board's `FullState`, measured locally ([WireBudget]), could fit, since a board that fits no better would be refused the
 * same way. A presence post doesn't count as a change. The next connection to settle clears
 * [tooLarge].
 *
 * **Identities.** Every edit names [author] through the transform (`it.openSurface(author, …)`):
 * it is the stable CRDT author for this process, online or offline. The Quilter's own replica id
 * is fresh per connection (`<author>#c<n>`), because a rebuilt Quilter restarts its delta seqs at
 * 1 and peers key them by that id. The fabric peer id is fresh per weave too ([SpokeLoom]), and it
 * is what [link] shows.
 *
 * **The mirror is identity-guarded**, as defence in depth. The pump that copies a connection's
 * state into the held board writes only while that connection is still the live one, checked
 * under [mutex], and reads the connection's current state rather than the emission. Parting a
 * connection already cancels its job under the same mutex, so a late mirror can't run; the guard
 * keeps an offline edit safe even if that ever changes.
 *
 * **A write too big for the link is refused, never dropped** (Review Focus 5). A seam drops a
 * broadcast over its `maxPayloadBytes` without a word, so each delta is weighed as the Quilter
 * will frame it ([WireBudget]), online against the live seam's budget ([maxPayloadBytes], or the
 * link's own where that is tighter), offline against [maxPayloadBytes], and a delta over it throws
 * [BoardWriteTooLarge] with the board unchanged and the link up. One write is the caller's to
 * fix; a board too large to sync as a whole is [tooLarge].
 *
 * **Failures are state.** A failed dial, a dial that outlasts [dialTimeout], a failed pump or a
 * failed seam close lands in
 * [lastFailure], and a failed pump also ends its connection, so a broken mirror can't let the held
 * board drift from the replica. The next connection to go live clears it.
 *
 * **Locking.** The held board, the live connection, [online] and [link] change together under
 * one [Mutex], which is never held across a suspension: weaving and closing happen outside it.
 * [lastFailure] is written from pump callbacks, which can't suspend, so it stands alone.
 *
 * @param clock the wall clock, kept for what the client will stamp (presence, Task 7). The Quilter
 *   keeps kuilt's monotonic clock for eviction.
 * @param random the redial backoff's jitter.
 * @param dialTimeout how long one [Loom.weave] may take. A dial that never answers (a socket
 *   accepted and never upgraded, or one closed mid-handshake) is cancelled at this bound, recorded
 *   as a [DialTimeoutException], and redialled on the backoff, so it can't leave the client
 *   offline for good.
 * @property maxPayloadBytes the largest Quilter payload a connection may send: [BOARD_PAYLOAD_BYTES],
 *   so no frame is over the host's [MAX_FRAME_BYTES]. A write whose delta is bigger is refused, and
 *   a `FullState` bigger is [tooLarge]. Tests pass a small one.
 * @param settleAfter how long a connection must stay live before its tear resets the backoff.
 */
@Suppress("LongParameterList")
class BoardClient(
    private val author: ReplicaId,
    private val loom: Loom,
    private val rendezvous: Rendezvous,
    private val scope: CoroutineScope,
    @Suppress("unused") private val clock: () -> Instant,
    initial: BoardState = BoardState.EMPTY,
    private val config: QuilterConfig = BEE_QUILTER_CONFIG,
    random: Random = Random.Default,
    private val dialTimeout: Duration = 5.seconds,
    val maxPayloadBytes: Int = BOARD_PAYLOAD_BYTES,
    private val settleAfter: Duration = 5.seconds,
) {
    init {
        require(dialTimeout.isPositive()) { "dialTimeout must be positive, was $dialTimeout" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive, was $maxPayloadBytes" }
        require(settleAfter.isPositive()) { "settleAfter must be positive, was $settleAfter" }
    }

    /** One connection: its seam, its Quilter, and the job they and their pumps run under. */
    internal class Connection(
        val seam: BoundedSeam,
        val quilter: Quilter<BoardState>,
        val job: Job,
        val scope: CoroutineScope,
    ) {
        /** Completes when the connection is over: its seam tore, a pump failed, or its Quilter closed. */
        val ended = CompletableDeferred<Unit>()

        /**
         * Whether it stayed live for the client's settle time. Written under the client's mutex, and
         * read by the redial loop after [end] has taken that mutex.
         */
        var settled = false

        /**
         * The writes published through [quilter], each under its next sequence number, so the last
         * one's is this count. Changed under the client's mutex.
         */
        var writes = 0L
    }

    private val backoff = ExponentialBackoff(base = 250.milliseconds, cap = 5.seconds, random = random)
    private val mutex = Mutex()
    private val held = MutableStateFlow(initial)
    private val _online = MutableStateFlow(false)
    private val _link = MutableStateFlow<String?>(null)
    private val _lastFailure = MutableStateFlow<Throwable?>(null)
    private val _tooLarge = MutableStateFlow<BoardSyncTooLarge?>(null)
    private val wanted = MutableStateFlow(false)
    private var live: Connection? = null
    private var connections = 0
    private var attempt = 0

    /** The board as this peer holds it, online or not. */
    val state: StateFlow<BoardState> = held.asStateFlow()

    /** Whether a connection to the relay is live. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** The live connection's fabric peer id, or null offline. Tests read it. */
    val link: StateFlow<String?> = _link.asStateFlow()

    /** What last went wrong with the link: a dial, a pump or a close. Null once a connection goes live. */
    val lastFailure: StateFlow<Throwable?> = _lastFailure.asStateFlow()

    /** Why the board can't sync, if it can't: a frame it would send is over the link's bound. */
    val tooLarge: StateFlow<BoardSyncTooLarge?> = _tooLarge.asStateFlow()

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

    /**
     * Applies [f]'s patch to the board, online or offline. Never waits on the network.
     *
     * @throws BoardWriteTooLarge if the patch's delta won't fit the board link; nothing is written
     */
    suspend fun mutate(f: (BoardState) -> Patch<BoardState>) {
        mutateOrSkip(f)
    }

    /**
     * [mutate], for a transform that may decline by returning null. Returns whether it wrote. The
     * decision is made against the very state the patch lands on.
     *
     * @throws BoardWriteTooLarge if the patch's delta won't fit the board link; nothing is written
     */
    suspend fun mutateOrSkip(f: (BoardState) -> Patch<BoardState>?): Boolean = mutex.withLock {
        val connection = live
        if (connection != null && connection.seam.state.value !is SeamState.Torn) {
            // Read per write: a seam's budget is a reading, not a lease (kuilt's Seam.maxPayloadBytes).
            val wrote = writeOnline(connection) { state ->
                f(state)?.also { WireBudget.check(connection.quilter.replica, it.delta, connection.seam.maxPayloadBytes) }
            }
            if (wrote != null) {
                if (wrote) connection.writes++
                held.value = connection.quilter.state.value
                return@withLock wrote
            }
        }
        // Offline, or the live Quilter closed itself before its tear reached the driver: the edit
        // goes onto the held board, and the next connection's FullState carries it.
        connection?.let { partLocked(it) }
        val patch = f(held.value) ?: return@withLock false
        WireBudget.check(ReplicaId("${author.value}#c${connections + 1}"), patch.delta, maxPayloadBytes)
        held.value = held.value.piece(patch.delta)
        true
    }

    /**
     * Waits until every peer of the live connection (on a spoke, the relay) has acknowledged every
     * write made on it, so a process may end without losing them. Unbounded: the caller bounds it.
     *
     * kuilt's `Quilter.mutate` doesn't return the sequence number it published, so this counts the
     * writes made through each connection's Quilter, the only local writes it numbers. A kuilt gap,
     * tracked as tractat-us/kuilt#3147: a Quilter caller can't wait for its own write to be
     * acknowledged. This workaround waits on that issue.
     *
     * @return true once they're acknowledged; false at once when offline (an offline write rides
     *   the next connection's FullState), or when the connection ends first
     */
    suspend fun awaitAcknowledged(): Boolean {
        val (connection, writes) = mutex.withLock { live?.let { it to it.writes } } ?: return false
        return coroutineScope {
            val acknowledged = async { connection.quilter.universalAckFlow.first { it >= writes } }
            val ended = async { connection.ended.await() }
            select {
                acknowledged.onAwait { true }
                ended.onAwait { false }
            }.also { coroutineContext.cancelChildren() }
        }
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
            // before [f] ran is that check (kuilt exposes no closed signal); anything [f] throws
            // is the caller's.
            if (ran) throw closed
            null
        }
    }

    private suspend fun stayConnected() {
        while (true) {
            openOrNull()?.let { ride(it) }
            delay(backoff.delay(attempt++))
        }
    }

    /**
     * Waits out [connection] and ends it. If its seam refused a payload over the bound, says why
     * the board can't sync, then waits until the board could fit: a board that fits no better
     * would be refused the same way.
     */
    private suspend fun ride(connection: Connection) {
        try {
            connection.ended.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { end(connection) }
            throw cancelled
        }
        val parted = withContext(NonCancellable) { end(connection) }
        if (connection.settled) attempt = 0
        val refusal = connection.seam.refusal() ?: return
        report(refusal)
        awaitRoom(parted, refusal.limit)
    }

    /** Says why the board can't sync. */
    private fun report(refusal: BoardSyncTooLarge) {
        _tooLarge.value = refusal
        recordFailure(refusal)
        log.warn { "${author.value}: ${refusal.message}; dialling again once the board could fit." }
    }

    /**
     * Suspends until the held board's `FullState` could fit [limit], measured from [parted], the
     * board as its connection parted, so an edit made while that connection closed is seen. Only
     * a change a presence post can't make is measured: a post every [Presence.EVERY] swaps one
     * small entry for another, so it can't bring a board under the bound, and measuring it would
     * cost a full encode each time (bee#65).
     */
    private suspend fun awaitRoom(parted: BoardState, limit: Int) {
        var board = parted
        while (WireBudget.fullStateBytes(author, board) > limit) {
            val measured = board.withoutPresence()
            board = held.first { it.withoutPresence() != measured }
        }
    }

    private suspend fun openOrNull(): Connection? {
        val seam = weaveOrNull() ?: return null
        val connection = try {
            mutex.withLock { wire(BoundedSeam(seam, maxPayloadBytes)).also { publish(it) } }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { closeQuietly(seam) }
            throw failure
        }
        connection.quilter.state.pumpIn(connection, "mirror") { mirrorIfLive(connection) }
        connection.seam.state.pumpIn(connection, "tear") { if (it is SeamState.Torn) connection.ended.complete(Unit) }
        // Synchronous with the refusal, so neither a cancelled scope nor [settle] can come between.
        connection.seam.refused.invokeOnCompletion { connection.ended.complete(Unit) }
        connection.scope.launch(CoroutineName("${connection.quilter.replica.value}-settle")) {
            delay(settleAfter)
            settle(connection)
        }
        return connection
    }

    /** Marks [connection] settled, if it is still live, so its tear resets the backoff. */
    private suspend fun settle(connection: Connection) = mutex.withLock {
        if (live !== connection || connection.ended.isCompleted) return@withLock
        connection.settled = true
        _tooLarge.value = null
    }

    /**
     * One dial, bounded by [dialTimeout], or null with the failure recorded. A timeout can land
     * after [Loom.weave] has returned and before the bound sees it (kotlinx.coroutines documents
     * the race), so a seam woven under a lost race is closed, never leaked.
     */
    private suspend fun weaveOrNull(): Seam? {
        var woven: Seam? = null
        val seam = try {
            withTimeoutOrNull(dialTimeout) { loom.weave(rendezvous).also { woven = it } } // suspends: outside the lock
        } catch (cancelled: CancellationException) {
            woven?.let { withContext(NonCancellable) { closeQuietly(it) } }
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure)
            return null
        }
        if (seam == null) {
            woven?.let { withContext(NonCancellable) { closeQuietly(it) } }
            recordFailure(DialTimeoutException(dialTimeout))
        }
        return seam
    }

    /** A fresh Quilter over [seam], seeded with the held board, on a new child job. Holds [mutex]. */
    private fun wire(seam: BoundedSeam): Connection {
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
            )
            return Connection(seam, quilter, job, connectionScope)
        } catch (failure: Throwable) {
            job.cancel()
            throw failure
        }
    }

    /**
     * Makes [connection] live, and resets the last failure. The redial backoff resets only once it
     * settles ([settle]). Holds [mutex].
     */
    private fun publish(connection: Connection) {
        check(live == null) { "${author.value} is already connected" }
        live = connection
        held.value = connection.quilter.state.value
        _link.value = connection.seam.selfId.value
        _lastFailure.value = null
        _online.value = true
    }

    /** Copies [connection]'s state into the held board, **only while it is still the live one**. */
    internal suspend fun mirror(connection: Connection) = mirrorIfLive(connection)

    private suspend fun mirrorIfLive(connection: Connection) {
        mutex.withLock { if (live === connection) held.value = connection.quilter.state.value }
    }

    /** The live connection, for the guard's test. */
    internal suspend fun liveConnection(): Connection? = mutex.withLock { live }

    /**
     * Ends [connection]: parts it if it's still live, then stops its job and closes its seam.
     * Returns the held board as it stood at the parting, before the close, which can take up to
     * [SEAM_CLOSE_TIMEOUT].
     */
    private suspend fun end(connection: Connection): BoardState {
        val parted = mutex.withLock {
            partLocked(connection)
            held.value
        }
        connection.job.cancelAndJoin()
        connection.quilter.close()
        closeQuietly(connection.seam)
        return parted
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

    /** Closes a parted [seam], bounded: nothing holds it any more, so a stuck close is recorded and left. */
    private suspend fun closeQuietly(seam: Seam) {
        try {
            withTimeoutOrNull(SEAM_CLOSE_TIMEOUT) { seam.close() } // suspends: always outside the lock
                ?: recordFailure(IllegalStateException("closing seam ${seam.selfId.value} took over $SEAM_CLOSE_TIMEOUT"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure)
        }
    }

    private fun recordFailure(failure: Throwable) {
        _lastFailure.value = failure
    }

    /**
     * Pumps [body] on [connection]'s scope. A failing body is recorded and ends the connection
     * (STOP), so a broken mirror never lets the held board drift from the replica.
     */
    private fun <T> StateFlow<T>.pumpIn(connection: Connection, name: String, body: suspend (T) -> Unit) {
        pumpIn(
            connection.scope,
            onFailure = { _, failure ->
                recordFailure(failure)
                connection.ended.complete(Unit)
                PumpVerdict.STOP
            },
            name = "${connection.quilter.replica.value}-$name",
            body = body,
        )
    }

    private companion object {
        val SEAM_CLOSE_TIMEOUT = 2.seconds
        val log = KotlinLogging.logger("us.tractat.bee.core.BoardClient")
    }
}

/**
 * The board can't sync: the client would have sent a frame of [bytes] bytes, over the link's bound
 * of [limit], and sent nothing instead. Not a write refused for its size: the board took the
 * write, and it's the board as a whole that can't reach the link.
 */
class BoardSyncTooLarge(val bytes: Int, val limit: Int) :
    Exception("can't sync: board too large ($bytes bytes to send, over the link's bound of $limit)")

/** A [BoardClient] dial that didn't answer within [timeout]; it was cancelled and goes round the redial. */
class DialTimeoutException(val timeout: Duration) : Exception("dial took over $timeout")
