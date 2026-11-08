package us.tractat.bee.host

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
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.RaftConfig
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The always-on host (spec §5.4): it serves the page at `/`, names its board at [BoardId.PATH],
 * relays the board at [BoardRelay.PATH], and runs the claims voter at [CLAIMS_PATH]. Invariant:
 * **the host writes to the board only through its own replica**; it has no write route. Its
 * writes are the claims mirror and the departed list, from the voter's fold, and the join
 * requests of LAN pairing ([JoinRequestSurface]), and nothing else.
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
     * Stops the host: join requests first, since they write through the relay, then the core, whose
     * voter's last mirror and server's last write are on the board before its last save.
     */
    override fun close() {
        runBlocking {
            joinRequests.cancelAndJoin()
            core.close()
        }
    }

    companion object {
        /** The claims voter's WebSocket route, its room's session name, and its server peer id. */
        const val CLAIMS_PATH: String = "/ws/claims"
        const val CLAIMS_SESSION: String = "claims"
        val CLAIMS_SERVER: PeerId = PeerId("bee-host")

        internal const val CSP_HEADER = "Content-Security-Policy"

        /**
         * The page's policy, sent with the page's files ([mountEndpoints]): no frame may load a
         * URL. A `Sketch` frame shows its `srcdoc` (which this policy doesn't block, in Chromium
         * or WebKit), but can't navigate itself to a URL, by `location` or a link (spec §5.6).
         * That isn't "no network": WebRTC and DNS hints sit outside CSP (bee#49). CSP has no
         * `navigate-to`, and the frame's own policy can't stop its navigation; the embedder's
         * `frame-src` does. `child-src` covers what falls back to it.
         *
         * Images load only from this host or `data:`: a card's Markdown can name an image
         * anywhere, and would otherwise make every viewer fetch it. Nothing else is restricted,
         * so the page's sockets and its pairing stream need nothing more.
         */
        const val PAGE_CSP: String = "frame-src 'none'; child-src 'none'; img-src 'self' data:"

        /**
         * Starts a host on [config]'s address, with its board and claims log under
         * [HostConfig.dataDir]. Background work runs on [scope]. [Departures] reads a crash off
         * the claims voter: a node it drops after the learner grace ([claimsLinkLoss], bee#28).
         */
        suspend fun start(scope: CoroutineScope, config: HostConfig): BeeHost {
            val clock = { Clock.System.now() }
            val stores = HostStores.onDisk(config.dataDir)
            // Task 10: keys live in this host's memory, so a restart re-pairs every LAN device.
            val pairing = Pairing(clock, SecureSecrets(SecureRandom()), budget = config.pairingBudget)
            val joinRequests = CompletableDeferred<JoinRequestSurface>()
            val desk = PairingDesk(pairing, joinRequests, Pairing.DWELL, clock)
            lateinit var endpoints: HostEndpoints
            val server = embeddedServer(Netty, port = config.port, host = config.bind) {
                endpoints = mountEndpoints(stores.boardId, pairing, config.siteRule(), PeerAddress.DIRECT, ArrivalAddress.DIRECT, desk)
            }
            server.start(wait = false)
            try {
                val bound = server.engine.resolvedConnectors().first().port
                val intake = AutoCloseable { server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000) }
                val core = HostCore.start(scope, endpoints.board, endpoints.claims, stores, intake, Dispatchers.Default, RaftConfig(), clock)
                val requests = JoinRequestSurface(core.board.relay, pairing, clock)
                val pairingJob = requests.start(scope)
                joinRequests.complete(requests)
                return BeeHost(bound, core, pairing, pairingJob)
            } catch (failure: Throwable) {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                throw failure
            }
        }
    }
}

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
