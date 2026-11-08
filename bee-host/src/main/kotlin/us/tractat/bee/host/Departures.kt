package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.Departures")

/**
 * Decides when an agent is dead (spec §6.4). Invariant: **only an agent is ever departed, and only
 * for a lost claims link it still holds or for a silence it isn't waiting through.**
 *
 * - **Crashed.** On [PartitionEvent.PeerLost] for a claims node the voter dropped after the
 *   learner grace ([claimsLinkLoss], bee#28), it departs the agent whose **current** presence
 *   entry still names that node. An agent that rebuilt its claims link has posted the new node, so
 *   the old node's loss matches nobody.
 * - **Quiet.** Every [CHECK_EVERY] it departs any agent whose `lastActedAt` is [quietAfter] old or
 *   more, unless its entry says it is waiting on a surface.
 * - **Never humans.** A person's tabs come and go without retiring them.
 *
 * Each identity is proposed at most once per [Departures]: the claim table already ignores a
 * second `Depart`, and a lingering presence entry would otherwise re-propose it on every check. A
 * proposal that fails is logged and not remembered, so the next lost link or check tries again.
 *
 * It decides; [depart] commits. `BeeHost` passes `ClaimsVoter::depart`.
 */
class Departures(
    private val events: Flow<PartitionEvent>,
    private val presence: suspend () -> List<PresenceEntry>,
    private val depart: suspend (PeerIdentity, DepartReason) -> Unit,
    private val quietAfter: Duration = 20.minutes,
    private val clock: () -> Instant,
) {
    init {
        require(quietAfter.isPositive()) { "quietAfter must be positive, was $quietAfter" }
    }

    private val mutex = Mutex()
    private val proposed = mutableSetOf<PeerIdentity>()

    /** Watches [events] and the quiet clock on [scope] until it is cancelled. */
    fun start(scope: CoroutineScope): Job {
        val job = Job(scope.coroutineContext[Job])
        val departures = CoroutineScope(scope.coroutineContext + job)
        events.filterIsInstance<PartitionEvent.PeerLost>().pumpIn(
            departures,
            onFailure = { phase, failure ->
                log.warn(failure) { "departures: lost-link handling failed ($phase)" }
                PumpVerdict.CONTINUE
            },
            name = "departures-lost-links",
        ) { lost -> lostLink(NodeId(lost.peerId.value)) }
        departures.launch(CoroutineName("departures-quiet")) {
            while (true) {
                delay(CHECK_EVERY)
                departQuiet()
            }
        }
        return job
    }

    private suspend fun lostLink(node: NodeId) {
        presence().filter { it.identity.kind == PeerKind.Agent && it.claimsNode == node }
            .forEach { propose(it.identity, DepartReason.Crashed) }
    }

    private suspend fun departQuiet() {
        val now = clock()
        presence().filter { it.identity.kind == PeerKind.Agent && !it.waiting && now - it.lastActedAt >= quietAfter }
            .forEach { propose(it.identity, DepartReason.Quiet) }
    }

    private suspend fun propose(identity: PeerIdentity, reason: DepartReason) {
        if (!mutex.withLock { proposed.add(identity) }) return
        try {
            depart(identity, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock { proposed.remove(identity) }
            log.warn(failure) { "departures: Depart($identity, $reason) failed; it is retried on the next signal" }
        }
    }

    companion object {
        /** How often the quiet clock is read: a quiet agent departs within this of [quietAfter]. */
        val CHECK_EVERY: Duration = 30.seconds
    }
}
