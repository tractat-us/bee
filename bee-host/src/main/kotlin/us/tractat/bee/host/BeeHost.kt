package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.ProcessTelemetry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.bee.mcp.HttpBridges
import us.tractat.kuilt.raft.RaftConfig
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The always-on host (spec §5.4): it serves the page at `/`, names its board at [BoardId.PATH],
 * relays the board at [BoardRelay.PATH], runs the claims voter at [CLAIMS_PATH], and serves MCP
 * over HTTP at [MCP_PATH], running a bridge for each session there ([HttpBridges]). Invariant:
 * **the host writes to the board only through its own replica**; it has no write route. Its
 * writes are the claims mirror and the departed list, from the voter's fold, the join
 * requests of LAN pairing ([JoinRequestSurface]), and the decisions orphaned claims ask for
 * ([OrphanDecisions]), and nothing else.
 *
 * Off loopback, the board and claims endpoints take only a device with a key, and from anywhere,
 * only a socket naming this host's board ([mountEndpoints]).
 *
 * Everything but the Netty server and pairing is a [HostCore]. The server is never parented to a caller's
 * coroutine (kuilt has hung on an `embeddedServer` parented to `runBlocking`), so [close] stops
 * it. Everything else runs on the scope given to [start], which the caller cancels.
 */
class BeeHost private constructor(
    /** The bound port: pass `port = 0` for an ephemeral one. */
    val port: Int,
    private val core: HostCore,
    /** LAN pairing (§5.7): it mints the keys the board and claims endpoints accept off loopback. */
    val pairing: Pairing,
    private val joinRequests: Job,
    private val orphanDecisions: Job,
    /** The bridges this host runs for MCP clients over HTTP, at [MCP_PATH]. */
    val bridges: HttpBridges,
    /** Records the board's changes into [telemetry] ([watchBoard]). */
    private val watch: Job,
    /** What the host records, in its data dir: closed after the board's last save, before the data dir goes. */
    private val telemetry: ProcessTelemetry,
    /** The host's hold on [HostConfig.dataDir], let go once [close] has saved the board. */
    private val dataDir: DataDirLock,
) : AutoCloseable {
    /** The claims voter at [CLAIMS_PATH]. */
    val voter: ClaimsVoter get() = core.voter

    /** The board this host keeps: minted when its stores keep none, or disagree ([HostStores.settle]). */
    val boardId: BoardId get() = core.board.id

    /** The host's CRDT author, fresh per boot. A [mutate] transform names it. */
    val author: ReplicaId get() = core.board.relay.replica

    /** The host's merged board. */
    val state: StateFlow<BoardState> get() = core.board.relay.state

    /** Writes to the board through the host's replica, as [author]. */
    @Suppress("RedundantSuspendModifier")
    suspend fun mutate(f: (BoardState) -> Patch<BoardState>) = core.board.relay.mutate(f)

    /**
     * Stops the host: the HTTP bridges first, each departing `Left` while the voter can still
     * commit it, then join requests and orphan decisions, since they write through the relay, then
     * the core, whose voter's last mirror and server's last write are on the board before its last
     * save. The data dir goes last, so the next host on it starts after that save.
     */
    override fun close() {
        dataDir.use {
            runBlocking {
                watch.cancelAndJoin()
                bridges.close()
                joinRequests.cancelAndJoin()
                orphanDecisions.cancelAndJoin()
                core.close()
            }
            telemetry.close()
        }
    }

    companion object {
        /** The claims voter's WebSocket route, its room's session name, and its server peer id. */
        const val CLAIMS_PATH: String = "/ws/claims"
        const val CLAIMS_SESSION: String = "claims"
        val CLAIMS_SERVER: PeerId = PeerId("bee-host")

        /** Where the host serves MCP over Streamable HTTP, for a Claude with nothing installed ([HttpBridges]). */
        const val MCP_PATH: String = HttpBridges.PATH

        internal const val CSP_HEADER = "Content-Security-Policy"

        /** The host's CRDT author for this boot, on its records: `bee.peer` is the steady "host". */
        const val REPLICA_ATTRIBUTE: String = "bee.replica"

        /**
         * The page's policy, sent with the page's files ([mountEndpoints]): no frame may load a
         * URL. A `Sketch` frame shows its `srcdoc` (which this policy doesn't block, in Chromium
         * or WebKit), but can't navigate itself to a URL, by `location` or a link (spec §5.6).
         * That isn't "no network": WebRTC and DNS hints sit outside CSP (bee#49). CSP has no
         * `navigate-to`, and the frame's own policy can't stop its navigation; the embedder's
         * `frame-src` does. `child-src` covers what falls back to it. Nothing else is restricted,
         * so the page's sockets and its pairing stream need nothing more.
         */
        const val PAGE_CSP: String = "frame-src 'none'; child-src 'none'"

        /**
         * Starts a host on [config]'s address, with its board, claims log and keys under
         * [HostConfig.dataDir]. Background work runs on [scope]. [Departures] reads a crash off
         * the claims voter: a node it drops after the learner grace ([claimsLinkLoss], bee#28).
         *
         * The host records into what [telemetry] opens in its data dir, once it holds the dir: each
         * socket dialled, each call refused, each change to its board ([recordCalls],
         * [watchBoard]), stamped with the board and the host's replica.
         *
         * @throws DataDirInUse if another host holds the data dir: this one touches nothing in it
         */
        suspend fun start(scope: CoroutineScope, config: HostConfig, telemetry: (Path) -> ProcessTelemetry = { ProcessTelemetry.off() }): BeeHost =
            start(scope, config, PeerAddress.DIRECT, telemetry)

        /**
         * [start], with the socket gate reading each call's peer from [peers]: a test's way to
         * have a dial on a loopback socket count as one from another machine, which a key must admit.
         */
        internal suspend fun start(
            scope: CoroutineScope,
            config: HostConfig,
            peers: PeerAddress,
            telemetry: (Path) -> ProcessTelemetry = { ProcessTelemetry.off() },
        ): BeeHost {
            val lock = DataDirLock.take(config.dataDir)
            val recording = telemetry(config.dataDir)
            return try {
                start(scope, config, peers, lock, recording)
            } catch (failure: Throwable) {
                recording.close()
                lock.close()
                throw failure
            }
        }

        /** [start], on the data dir [lock] holds: nothing opens [HostConfig.dataDir] without one. */
        private suspend fun start(scope: CoroutineScope, config: HostConfig, peers: PeerAddress, lock: DataDirLock, telemetry: ProcessTelemetry): BeeHost {
            val clock = { Clock.System.now() }
            val stores = HostStores.onDisk(config.dataDir)
            telemetry.resource.set(Attr.BOARD, stores.boardId.value)
            val secrets = SecureSecrets(SecureRandom())
            // bee#106: every key outlives a restart; config.keyReset is how one stops admitting.
            val keys = HostKeys.boot(KeyFile(config.dataDir.resolve(KeyFile.NAME)), secrets, config.keyReset)
            val pairing = Pairing(clock, secrets, keys, budget = config.pairingBudget)
            val joinRequests = CompletableDeferred<JoinRequestSurface>()
            val desk = PairingDesk(pairing, joinRequests, Pairing.DWELL, clock)
            val bridges = HttpBridges(scope, clock, SecureRandom().asKotlinRandom(), config.mcpIdleAfter, maxSessions = config.mcpMaxSessions, telemetry = telemetry.telemetry)
            lateinit var endpoints: HostEndpoints
            val server = embeddedServer(Netty, port = config.port, host = config.bind) {
                recordCalls(telemetry.telemetry, stores.boardId, peers)
                endpoints = mountEndpoints(stores.boardId, pairing, config.siteRule(), peers, ArrivalAddress.DIRECT, desk, config.reach.invitation())
                bridges.mount(this)
            }
            server.start(wait = false)
            try {
                val bound = server.engine.resolvedConnectors().first().port
                val intake = AutoCloseable { server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000) }
                val core = HostCore.start(scope, endpoints.board, endpoints.claims, stores, intake, Dispatchers.Default, RaftConfig(), clock)
                val requests = JoinRequestSurface(core.board.relay, pairing, clock)
                val pairingJob = requests.start(scope)
                joinRequests.complete(requests)
                val decisions = hostOrphanDecisions(scope, core.board.relay, clock).start(scope)
                val relay = core.board.relay
                telemetry.resource.set(REPLICA_ATTRIBUTE, relay.replica.value)
                val watch = scope.watchBoard(relay.state, relay.spokes, PeerId(relay.replica.value), telemetry.telemetry)
                return BeeHost(bound, core, pairing, pairingJob, decisions, bridges, watch, telemetry, lock)
            } catch (failure: Throwable) {
                bridges.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                throw failure
            }
        }
    }
}

/**
 * The host's [OrphanDecisions]: each orphan's decision offers the peers attached now, as the
 * host's replica sees them ([LivePresence]).
 */
internal fun hostOrphanDecisions(scope: CoroutineScope, relay: BoardRelay, clock: () -> Instant): OrphanDecisions {
    val roster = LivePresence(relay.state, clock, scope) { failure -> orphanLog.warn(failure) { "orphan decisions: the roster missed a board change" } }
    return OrphanDecisions(relay, { roster.peers().keys.toList() }, clock)
}

private val orphanLog = KotlinLogging.logger("us.tractat.bee.host.OrphanDecisions")

/** The voter's [ClaimMirror] onto [relay]'s board: the claims mirror and the departed list (A2). */
fun boardMirror(relay: BoardRelay): ClaimMirror = ClaimMirror { table -> relay.mutate { it.mirrorClaims(relay.replica, table) } }

/**
 * The host's [Departures]: a crash is a claims node [voter] drops ([claimsLinkLoss], bee#28),
 * matched against each poster's newest entry on [board]; `Depart`s go through [voter].
 *
 * Not the live roster ([us.tractat.bee.core.LivePresence]): an entry leaves it a TTL (30 s) after
 * its last post, and the voter drops a node a learner grace (30 s) after its link went, so a
 * killed bridge's entry has always aged out by the time its node is dropped.
 *
 * Entries are never pruned and a booted [Departures] has proposed nothing, so an identity the board
 * lists as departed is dropped here: the log rebuilds that list on boot, and re-proposing would
 * cost a durable append per historical agent per boot.
 *
 * The boot check (bee#44) watches [board]'s presence slots from boot, and reads its holders off
 * [voter]'s table. A slot on [board] says nothing on its own: a surviving peer's first-contact push
 * brings back entries made before the host went down, a dead agent's among them. Only a slot that
 * advances while this boot watches it shows its poster is alive.
 */
internal fun hostDepartures(voter: ClaimsVoter, board: StateFlow<BoardState>, clock: () -> Instant): Departures = Departures(
    claimsLinkLoss(voter.membership, clock),
    presence = { board.value.undeparted() },
    postings = board.map { it.presence },
    holders = { voter.table.value.holders() },
    depart = voter::depart,
    clock = clock,
)

private fun BoardState.undeparted(): List<PresenceEntry> {
    val gone = departures().map { it.identity }.toSet()
    return latestPresence().filterNot { it.identity in gone }
}

/** Whoever holds a claim now. An orphaned claim's holder is retired, so it isn't one. */
private fun ClaimTable.holders(): Set<PeerIdentity> =
    targets.values.filterIsInstance<ClaimStatus.Held>().map { it.holder }.toSet()
