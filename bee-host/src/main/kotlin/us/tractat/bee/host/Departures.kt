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
 * - **Crashed, unseen since boot** (bee#44). Once, [bootGrace] after [start], it departs any agent
 *   among [holders] that has no [presence] entry. An agent that died while the host was down
 *   leaves neither an entry, since presence isn't saved, nor a link whose loss names it.
 * - **Never humans.** A person's tabs come and go without retiring them.
 *
 * Each identity is proposed at most once per [Departures]: the claim table already ignores a
 * second `Depart`, and a lingering presence entry would otherwise re-propose it on every check. A
 * proposal that fails is logged and not remembered, so the next lost link or check tries again;
 * for the boot check, that is the next [CHECK_EVERY], while the holder is still unseen. Each
 * proposal is logged.
 *
 * It decides; [depart] commits. `BeeHost` passes `ClaimsVoter::depart`, for [holders] the agents
 * holding a claim in the committed table, and for [presence] each poster's newest entry, however
 * old (`hostDepartures`): a crashed agent's entry has aged out of the live roster by the time the
 * voter drops its node.
 */
class Departures(
    private val events: Flow<PartitionEvent>,
    private val presence: suspend () -> List<PresenceEntry>,
    private val holders: suspend () -> Set<PeerIdentity>,
    private val depart: suspend (PeerIdentity, DepartReason) -> Unit,
    private val quietAfter: Duration = 20.minutes,
    private val bootGrace: Duration = BOOT_GRACE,
    private val clock: () -> Instant,
) {
    init {
        require(quietAfter.isPositive()) { "quietAfter must be positive, was $quietAfter" }
        require(bootGrace.isPositive()) { "bootGrace must be positive, was $bootGrace" }
    }

    private val mutex = Mutex()
    private val proposed = mutableSetOf<PeerIdentity>()

    /** Watches [events], the quiet clock and the boot grace on [scope] until it is cancelled. */
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
        departures.launch(CoroutineName("departures-boot")) {
            delay(bootGrace)
            departUnseen()
        }
        return job
    }

    /** The boot check: one pass, then again each [CHECK_EVERY] only for a holder whose proposal failed. */
    private suspend fun departUnseen() {
        var pending = unseenHolders()
        log.info { "departures: boot grace of $bootGrace over; claim holders unseen since boot: ${pending.ifEmpty { "none" }}" }
        while (pending.isNotEmpty()) {
            pending = pending.filterNot { propose(it, DepartReason.Crashed) }.toSet()
            if (pending.isEmpty()) return
            delay(CHECK_EVERY)
            pending = unseenHolders() intersect pending
        }
    }

    private suspend fun unseenHolders(): Set<PeerIdentity> {
        val seen = presence().map { it.identity }.toSet()
        return holders().filter { it.kind == PeerKind.Agent && it !in seen }.toSet()
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

    /** Proposes [identity]'s departure, unless it already was. False only if the proposal failed. */
    private suspend fun propose(identity: PeerIdentity, reason: DepartReason): Boolean {
        if (!mutex.withLock { proposed.add(identity) }) return true
        return try {
            depart(identity, reason)
            log.info { "departures: proposed Depart($identity, $reason)" }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock { proposed.remove(identity) }
            log.warn(failure) { "departures: Depart($identity, $reason) failed; it is retried on the next signal" }
            false
        }
    }

    companion object {
        /** How often the quiet clock is read: a quiet agent departs within this of [quietAfter]. */
        val CHECK_EVERY: Duration = 30.seconds

        /**
         * How long after boot a claim holder has to show up before it is departed (bee#44).
         *
         * A live bridge shows up well inside it. Its board link redials at most 5 s apart, with a
         * 5 s dial timeout, and its entry is on the board it pushes on reconnect and re-posted
         * every [us.tractat.bee.core.Presence.EVERY] (5 s). So the entry reaches the host within
         * about 15 s of the host taking connections, and the grace is four times that. Twice the
         * [ClaimsVoter.LEARNER_GRACE], it also outlasts the voter's removal of the nodes it
         * restored, so the lost-link rule has had its turn first. The cost of the margin is that a
         * dead holder's claims stay held for a minute after boot.
         */
        val BOOT_GRACE: Duration = ClaimsVoter.LEARNER_GRACE * 2
    }
}
