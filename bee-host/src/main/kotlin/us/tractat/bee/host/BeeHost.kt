package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimStatus
import us.tractat.bee.core.ClaimTable
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.bee.mcp.Caller
import us.tractat.bee.mcp.HttpBridges
import us.tractat.bee.mcp.KeptIdentities
import us.tractat.bee.mcp.KeptSessionFile
import us.tractat.kuilt.raft.RaftConfig
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.time.Duration
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
    /** Departs each session the last host kept that no client came back for ([retireUnclaimed]). */
    private val unclaimed: Job,
    /** The host's hold on [HostConfig.dataDir], let go once [close] has saved the board. */
    private val dataDir: DataDirLock,
    /** The key the host's own bridges dial with, fresh per boot and held in memory alone: a test's, to prove it never reaches a log. */
    internal val bridgeKey: DeviceKey,
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
     * Stops the host: the HTTP bridges first, departing none, so each comes back as itself on the
     * next host (bee#126), though one whose client had just left still commits its `Left`; then
     * the retiring of kept sessions nobody came back for, join requests and orphan decisions, since
     * they write through the voter or the relay, then the core, whose voter's last mirror and
     * server's last write are on the board before its last save. The data dir goes last, so the
     * next host on it starts after that save.
     */
    override fun close() {
        dataDir.use {
            runBlocking {
                bridges.close()
                unclaimed.cancelAndJoin()
                joinRequests.cancelAndJoin()
                orphanDecisions.cancelAndJoin()
                core.close()
            }
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
         * @throws DataDirInUse if another host holds the data dir: this one touches nothing in it
         */
        suspend fun start(scope: CoroutineScope, config: HostConfig): BeeHost = start(scope, config, config.reach.peers())

        /**
         * [start], with the socket gate reading each call's peer from [peers]: a test's way to
         * have a dial on a loopback socket count as one from another machine, which a key must admit.
         */
        internal suspend fun start(scope: CoroutineScope, config: HostConfig, peers: PeerAddress): BeeHost {
            val lock = DataDirLock.take(config.dataDir)
            return try {
                start(scope, config, peers, lock)
            } catch (failure: Throwable) {
                lock.close()
                throw failure
            }
        }

        /** [start], on the data dir [lock] holds: nothing opens [HostConfig.dataDir] without one. */
        private suspend fun start(scope: CoroutineScope, config: HostConfig, peers: PeerAddress, lock: DataDirLock): BeeHost {
            val clock = { Clock.System.now() }
            val stores = HostStores.onDisk(config.dataDir)
            val secrets = SecureSecrets(SecureRandom())
            // The host's own bridges' key, fresh per boot and held in memory alone: on a public host
            // their loopback sockets need one like anyone's.
            val bridgeKey = secrets.key()
            // bee#106: every key outlives a restart; config.keyReset is how one stops admitting.
            val keys = HostKeys.boot(KeyFile(config.dataDir.resolve(KeyFile.NAME)), secrets, config.keyReset, config.reach.joinLink(), held = config.reach.heldKeys() + bridgeKey)
            val pairing = Pairing(clock, secrets, keys, budget = config.pairingBudget)
            val joinRequests = CompletableDeferred<JoinRequestSurface>()
            // The board the voter's departures are mirrored onto, once the core is up. Until then no
            // departure is known, and a kept identity a session takes that was retired is retired
            // again at its first write, as any bridge's is (`BeeBridge.beforeWrite`).
            val board = AtomicReference<StateFlow<BoardState>?>()
            val kept = KeptIdentities(
                KeptSessionFile(config.dataDir.resolve(KeptSessionFile.NAME)),
                retired = { board.get()?.value?.departures().orEmpty().map { it.identity }.toSet() },
                clock = clock,
            )
            // Every key stopped admitting, so only a session kept with no key, on this machine, can
            // come back: the rest are retired at the first check, not held for the whole window. A
            // new link alone changes nothing here, since no session on the join link's key is kept.
            if (config.keyReset == KeyReset.ForgetDevices) kept.endWaits { it.caller != Caller.Loopback }
            val bridges = HttpBridges(scope, clock, SecureRandom().asKotlinRandom(), kept, keys::kindOf, bridgeKey.value, config.mcpIdleAfter, maxSessions = config.mcpMaxSessions)
            val roster = CompletableDeferred<LivePresence>()
            val admitters = when (config.reach) {
                // Nothing waits on a hosted board for someone who isn't there (Iain, 2026-11-08).
                is Reach.Public -> PeopleAttached(roster)
                Reach.Lan, Reach.Loopback -> Admitters.WHOEVER_COMES
            }
            val desk = PairingDesk(pairing, joinRequests, Pairing.DWELL, clock, admitters)
            // A hosted board's invites (#215) sit beside its keys, and only a hosted board keeps any.
            val invitation = config.reach.invitation { Invites.boot(InviteFile(config.dataDir.resolve(InviteFile.NAME)), config.keyReset, clock, secrets, keys) }
            lateinit var endpoints: HostEndpoints
            val server = embeddedServer(Netty, port = config.port, host = config.bind) {
                endpoints = mountEndpoints(stores.boardId, pairing, config.siteRule(), peers, ArrivalAddress.DIRECT, desk, invitation)
                bridges.mount(this)
            }
            server.start(wait = false)
            try {
                val bound = server.engine.resolvedConnectors().first().port
                val intake = AutoCloseable { server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000) }
                val core = HostCore.start(
                    scope,
                    endpoints.board,
                    endpoints.claims,
                    stores,
                    intake,
                    Dispatchers.Default,
                    RaftConfig(),
                    clock,
                    waitingForClient = kept::waitingIdentities,
                )
                board.set(core.board.relay.state)
                val requests = JoinRequestSurface(core.board.relay, pairing, clock)
                val pairingJob = requests.start(scope)
                joinRequests.complete(requests)
                roster.complete(LivePresence(core.board.relay.state, clock, scope) { failure -> rosterLog.warn(failure) { "admitters: the roster missed a board change" } })
                val decisions = hostOrphanDecisions(scope, core.board.relay, clock).start(scope)
                val unclaimed = retireUnclaimed(scope, kept, core.voter, config.mcpUnclaimedAfter, clock)
                return BeeHost(bound, core, pairing, pairingJob, decisions, bridges, unclaimed, lock, bridgeKey)
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

private val unclaimedLog = KotlinLogging.logger("us.tractat.bee.host.UnclaimedSessions")

/**
 * Departs each MCP-over-HTTP session the last host kept ([KeptIdentities]) that no session picked
 * up within [after], `Quiet`, through [voter], then forgets it (bee#126). Nothing else would: no
 * peer posts for it on this board, so `Departures`' quiet rule never sees it, and a second one
 * under the same name and key would keep that URL from ever resuming. A `Depart` that fails is
 * logged and tried again at the next check.
 */
internal fun retireUnclaimed(scope: CoroutineScope, kept: KeptIdentities, voter: ClaimsVoter, after: Duration, clock: () -> Instant): Job =
    scope.launch(CoroutineName("unclaimed-sessions")) {
        val every = minOf(Departures.CHECK_EVERY, after / 4)
        while (true) {
            delay(every)
            kept.unclaimed(clock() - after).forEach { session ->
                try {
                    voter.depart(session.current, DepartReason.Quiet)
                    kept.forget(session)
                    unclaimedLog.info { "${session.current}'s client never came back within $after; departed it Quiet." }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    unclaimedLog.warn(failure) { "Couldn't depart ${session.current}, kept for a client that never came back; trying again." }
                }
            }
        }
    }

private val rosterLog = KotlinLogging.logger("us.tractat.bee.host.Admitters")

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
 *
 * [waitingForClient] are the HTTP sessions' identities the last host kept that no session has
 * picked up yet ([KeptIdentities.waitingIdentities]): [retireUnclaimed] judges those, not this.
 */
internal fun hostDepartures(
    voter: ClaimsVoter,
    board: StateFlow<BoardState>,
    clock: () -> Instant,
    waitingForClient: () -> Set<PeerIdentity>,
): Departures = Departures(
    claimsLinkLoss(voter.membership, clock),
    presence = { board.value.undeparted() },
    postings = board.map { it.presence },
    holders = { voter.table.value.holders() },
    depart = voter::depart,
    clock = clock,
    waitingForClient = waitingForClient,
)

private fun BoardState.undeparted(): List<PresenceEntry> {
    val gone = departures().map { it.identity }.toSet()
    return latestPresence().filterNot { it.identity in gone }
}

/** Whoever holds a claim now. An orphaned claim's holder is retired, so it isn't one. */
private fun ClaimTable.holders(): Set<PeerIdentity> =
    targets.values.filterIsInstance<ClaimStatus.Held>().map { it.holder }.toSet()
