package us.tractat.bee.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
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
 * flood to every other spoke. Invariant: **nothing a weave starts outlives the seam it returns.**
 *
 * The shape is kuilt's demo `RelaySpokeLoom`, which is demo-only. A published hub-spoke client
 * loom is the kuilt tracking-issue item "A published hub-spoke client loom" (tractat-us/kuilt#3119).
 * It differs from the demo in two ways, both from #2869's reconnect-shape spike:
 *
 * - **Each weave's gossip loops run on a child job of their own**, and the returned seam's
 *   [Seam.close] cancels and joins that job. `GossipSeam.close` closes only its base, and the loops
 *   `start` launched would otherwise stay on [scope] for as long as it lives: two per reconnect.
 *   A seam that tears by itself is cancelled too, by a watcher on its state.
 * - **Each weave dials under a fresh peer id**, `<peerName>-<uuid>`. The hub's gossip dedup never
 *   forgets an origin, and a fresh `GossipSeam` restarts its flood seq at 1, so a spoke returning
 *   under an old id would lose its first frames as duplicates. The stable identity is the
 *   `BoardClient`'s CRDT author, never this id.
 *
 * @param scope the parent of every weave's loops; the caller owns it.
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
     * Dials the hub over [httpClient], which must have Ktor's `WebSockets` plugin installed. The
     * caller owns [httpClient] and [scope].
     */
    constructor(
        httpClient: HttpClient,
        scope: CoroutineScope,
        peerName: String,
        random: Random,
        clock: () -> Instant,
    ) : this(
        scope = scope,
        peerName = peerName,
        random = random,
        clock = clock,
        dial = { id, rendezvous -> KtorMeshClientLoom(httpClient, Dispatchers.Default, id).weave(rendezvous) },
    )

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val base = dial(PeerId("$peerName-${Uuid.random()}"), rendezvous)
        val loops = SupervisorJob(scope.coroutineContext[Job])
        val loopScope = scope + loops
        // Zero jitter, as kuilt's RelaySpokeLoom: a spoke's only neighbour is the hub, so jitter
        // only opens a window after connecting in which a broadcast has no flood target.
        val gossip = GossipSeam(base = base, random = random, clock = clock, jitter = Duration.ZERO..Duration.ZERO)
        gossip.start(loopScope)
        loopScope.launch {
            gossip.state.first { it is SeamState.Torn }
            loops.cancel()
        }
        return SpokeSeam(gossip, loops)
    }
}

/**
 * A spoke's [GossipSeam] whose [close] also stops the [loops] its `start` launched, and returns
 * only once they have stopped. It joins rather than only cancelling, because the tear watcher may
 * cancel the same job at the same moment, and a `cancel()` that loses that race returns while
 * the children are still active.
 */
private class SpokeSeam(private val gossip: GossipSeam, private val loops: Job) : Seam by gossip {
    override suspend fun close(reason: CloseReason) {
        try {
            gossip.close(reason)
        } finally {
            loops.cancelAndJoin()
        }
    }
}
