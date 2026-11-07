package us.tractat.bee.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.websocket.KtorMeshClientLoom
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A peer's way onto the board relay: every [weave] dials a fresh spoke into the host's
 * `hostedOverlay` hub and wraps it in a started [GossipSeam], so a broadcast rides the hub's
 * flood to every other spoke.
 *
 * This is kuilt's demo `RelaySpokeLoom`, which is demo-only. A published hub-spoke client loom is
 * the kuilt tracking-issue item "A published hub-spoke client loom" (tractat-us/kuilt#3119).
 *
 * - **Each weave dials under a fresh peer id**, `<peerName>-<uuid>`. The hub's gossip dedup never
 *   forgets an origin, and a fresh `GossipSeam` restarts its flood seq at 1, so a spoke returning
 *   under an old id would lose its first frames as duplicates. The stable identity is the
 *   [BoardClient]'s CRDT author, never this id.
 * - **A weave's gossip loops stop with its seam.** #2869's reconnect-shape spike found two loops
 *   per closed `GossipSeam` left on the scope `start` was given (kuilt#2904), and so started each
 *   spoke's gossip on the connection's child scope. kuilt#2950 (in the pinned kuilt) stops them on
 *   close and on a base tear, so one long-lived [scope] is safe again.
 *   `BoardClientTest.reconnectLeaksNoGossipCoroutines` pins it.
 *
 * @param scope runs each spoke's gossip loops; the caller owns it.
 * @param random mints each weave's peer id and seeds its gossip.
 * @param dial opens the base spoke under the given peer id. The public constructor dials a real
 *   WebSocket; tests dial an in-memory connection into an in-memory hub.
 */
@OptIn(ExperimentalUuidApi::class)
class SpokeLoom(
    private val scope: CoroutineScope,
    private val peerName: String,
    private val random: Random,
    private val clock: () -> Instant,
    private val dial: suspend (PeerId, Rendezvous) -> Seam,
) : Loom {
    /**
     * Dials the hub over [httpClient], which must have Ktor's `WebSockets` plugin installed, with
     * each spoke's read loop on [dispatcher]. The caller owns [httpClient] and [scope].
     */
    constructor(
        httpClient: HttpClient,
        dispatcher: CoroutineDispatcher,
        scope: CoroutineScope,
        peerName: String,
        random: Random,
        clock: () -> Instant,
    ) : this(
        scope = scope,
        peerName = peerName,
        random = random,
        clock = clock,
        dial = { id, rendezvous -> KtorMeshClientLoom(httpClient, dispatcher, id).weave(rendezvous) },
    )

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val base = dial(PeerId("$peerName-${Uuid.fromByteArray(random.nextBytes(Uuid.SIZE_BYTES))}"), rendezvous)
        // Zero jitter, as kuilt's RelaySpokeLoom: a spoke's only neighbour is the hub, so jitter
        // only opens a window after connecting in which a broadcast has no flood target.
        return GossipSeam(base = base, random = random, clock = clock, jitter = Duration.ZERO..Duration.ZERO)
            .also { it.start(scope) }
    }
}
