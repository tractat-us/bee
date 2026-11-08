package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.plus
import us.tractat.bee.core.BEE_QUILTER_CONFIG
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.persist.QuiltedPersistence
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.FileChannelDurableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("us.tractat.bee.host.HostBoard")

/**
 * Everything the host runs but its server: the [board], the claims [voter] mirroring onto it, and
 * the departures reading both. [BeeHost] puts a Netty server in front of it; tests run it in
 * memory. Invariant: **[close] saves the board last, whatever fails before it, and after the
 * server has stopped taking writes.**
 */
class HostCore private constructor(
    val board: HostBoard,
    val voter: ClaimsVoter,
    private val departures: Job,
    private val intake: AutoCloseable,
) {
    /** Stops departures and the voter, then [intake], then saves the board one last time. */
    suspend fun close() = withContext(NonCancellable) {
        try {
            departures.cancelAndJoin()
            voter.close()
        } finally {
            try {
                intake.close()
            } finally {
                board.close()
            }
        }
    }

    companion object {
        /**
         * Opens the board from [stores], relays it to [boardSource], and starts the voter on
         * [claimsHost]. Background work runs on [scope]. [intake] is what brings writes in from
         * outside, the server: [close] stops it before the board's last save.
         */
        @Suppress("LongParameterList")
        suspend fun start(
            scope: CoroutineScope,
            boardSource: ConnectionSource,
            claimsHost: RoomHost,
            stores: HostStores,
            intake: AutoCloseable,
            dispatcher: CoroutineContext,
            raftConfig: RaftConfig,
            clock: () -> Instant,
            random: Random = Random.Default,
            quilterConfig: QuilterConfig = BEE_QUILTER_CONFIG,
        ): HostCore {
            val board = HostBoard.open(scope, boardSource, dispatcher, stores.board, stores.boardId, clock, random, quilterConfig)
            val voter = ClaimsVoter.start(scope, claimsHost, stores.claims, raftConfig, boardMirror(board.relay), clock)
            val departures = hostDepartures(voter, board.relay.state, clock).start(scope)
            return HostCore(board, voter, departures, intake)
        }
    }
}

/**
 * Where the host keeps what outlives a boot: the board's snapshot and the claims log, and the
 * [boardId] the two belong to together. Invariant: **both stores record [boardId].** A snapshot
 * never meets a claims log it wasn't mirrored from ([BoardId]).
 */
class HostStores private constructor(val board: DurableStore, val claims: DurableStore, val boardId: BoardId) {
    companion object {
        /**
         * [board] and [claims], under the board id both record. If either records none, or they
         * disagree, a new board: its id is recorded in both, claims first. A crash between the two
         * writes leaves them disagreeing, so the next boot mints again. The exception is data from
         * before bee#46: `board/` records an id and `claims/` holds a Raft log (`raft/meta`) with
         * none. That snapshot was mirrored from that log, so the log adopts the id.
         */
        suspend fun settle(board: DurableStore, claims: DurableStore): HostStores {
            val kept = BoardId.keptIn(board)
            val logged = BoardId.keptIn(claims)
            if (kept != null && logged == null && claims.read(DurableStoreRaftStorage.META_KEY) != null) {
                // A claims log with no id predates bee#46. The snapshot's mirror came from this log, so they belong together.
                log.info { "Adopting board $kept into claims/ (data from before bee#46)." }
                BoardId.recordIn(claims, kept)
                return HostStores(board, claims, kept)
            }
            if (kept == null && logged != null) {
                log.warn { "board/ keeps no board id, so it was moved aside or wiped; claims/ keeps $logged. This is a new board, and claims/ is re-mirrored." }
            }
            val id = kept?.takeIf { it == logged } ?: BoardId.mint().also { minted ->
                if (kept != null) {
                    log.warn { "The claims log isn't board $kept's (it names ${logged ?: "none"}), so this is a new board, $minted. Board $kept's snapshot stays in board/." }
                }
                BoardId.recordIn(claims, minted)
                BoardId.recordIn(board, minted)
            }
            return HostStores(board, claims, id)
        }

        /** Each in its own [FileChannelDurableStore] under [dataDir], `board/` and `claims/`, [settle]d. */
        suspend fun onDisk(dataDir: Path): HostStores =
            settle(board = fileStore(dataDir.resolve("board")), claims = fileStore(dataDir.resolve("claims")))

        private fun fileStore(dir: Path) = FileChannelDurableStore(Files.createDirectories(dir).toFile())
    }
}

/**
 * The host's board, kept in a [DurableStore] (Task 9). Invariant: **a host boots with the board
 * it last saved, and mints under an author no earlier boot used.** [open] restores the snapshot as
 * the relay's `initial` under a fresh `host-<uuid>`: a reused id would restart its delta seqs
 * under a name the spokes already hold cursors for. The board is saved on change, without
 * presence, and once more on [close].
 *
 * The board's [id] is the one its stores settled on ([HostStores.settle]): it changes only when
 * `board/` or `claims/` is wiped or moved aside. Pages key their copies by it ([BoardId]).
 *
 * A snapshot that doesn't decode stops the boot rather than start an empty board over it. To boot
 * anyway, move the data dir's `board/` aside: the host starts an empty board under a new id, and
 * re-mirrors claims from `claims/`.
 */
class HostBoard private constructor(
    val id: BoardId,
    val relay: BoardRelay,
    private val saver: QuiltedPersistence<BoardState>.Saver,
    private val reporter: Job,
) {
    /** Saves the board one last time, then closes the relay. A failed save is logged: the host is going anyway. */
    suspend fun close() {
        try {
            saver.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.error(failure) { "The board's last save failed: what changed since the save before is lost." }
        } finally {
            reporter.cancel()
            relay.close()
        }
    }

    companion object {
        /**
         * Restores board [id] from [store] and relays it to spokes from [source]. Background work runs on [scope].
         *
         * @throws IllegalStateException if the snapshot in [store] doesn't decode, whether as a
         *   serialization failure or a nested decoder's own refusal
         */
        @OptIn(ExperimentalUuidApi::class)
        @Suppress("LongParameterList")
        suspend fun open(
            scope: CoroutineScope,
            source: ConnectionSource,
            dispatcher: CoroutineContext,
            store: DurableStore,
            id: BoardId,
            clock: () -> Instant,
            random: Random,
            config: QuilterConfig,
        ): HostBoard {
            val author = ReplicaId("host-${Uuid.random()}")
            val snapshot = QuiltedPersistence(store, id.snapshotKey, BoardState.serializer())
            val initial = try {
                snapshot.load { it.withReplica(author) } ?: BoardState.EMPTY
            } catch (unreadable: IllegalArgumentException) {
                throw IllegalStateException(
                    "Board $id's snapshot doesn't decode. Move the data dir's board/ aside to boot an empty board.",
                    unreadable,
                )
            }
            val relay = BoardRelay.hosted(scope, source, dispatcher, author, clock, random, initial, config)
            log.info { "board $id restored as ${author.value}: ${initial.surfaces.keys.size} surfaces, ${initial.records.size} records" }
            val saving = scope + CoroutineExceptionHandler { _, bug -> log.error(bug) { "Saving the board hit a bug, so the board is no longer saved." } }
            val saver = snapshot.saveOnChange(relay.state, saving, BoardState::withoutPresence)
            val reporter = scope.launch {
                saver.lastFailure.filterNotNull().collect { log.error(it) { "Saving the board failed. It is tried again in a moment." } }
            }
            return HostBoard(id, relay, saver, reporter)
        }
    }
}
