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
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.Departures")

/**
 * Decides when an agent is dead (spec §6.4). Invariant: **only an agent is ever departed, and only
 * for a lost claims link it still holds, for a silence it isn't waiting through, or for holding a
 * claim through the boot grace without posting to this boot.**
 *
 * - **Crashed.** On [PartitionEvent.PeerLost] for a claims node the voter dropped after the
 *   learner grace ([claimsLinkLoss], bee#28), it departs the agent whose **current** presence
 *   entry still names that node. An agent that rebuilt its claims link has posted the new node, so
 *   the old node's loss matches nobody.
 * - **Quiet.** Every [CHECK_EVERY] it departs any agent whose `lastActedAt` is [quietAfter] old or
 *   more, unless its entry says it is waiting on a surface.
 * - **Crashed, unseen since boot** (bee#44). Once, [BOOT_GRACE] after [start], it departs any
 *   agent among [holders] that hasn't posted to this boot: none of its slots in [postings] has
 *   advanced past the clock it had when this [Departures] first saw the slot. An agent that died
 *   while the host was down leaves no link whose loss names it, and its last entry, which a
 *   surviving peer's first-contact push brings back, never advances. A live agent re-posts every
 *   [us.tractat.bee.core.Presence.EVERY], so its slot advances within that of first being seen.
 *   An entry's presence alone would count a dead agent as seen once a peer pushed it back.
 * - **Never humans.** A person's tabs come and go without retiring them.
 * - **One agent, its newest entry.** The first two rules read each agent's newest entry, by
 *   `lastActedAt`, across every slot it posted from (bee#126). An HTTP session that comes back from a
 *   host restart as itself posts from a new replica on a new claims node, while a surviving peer
 *   pushes its old slot back; that slot's node and silence are the old session's, not this one's.
 *
 * Each identity is proposed at most once per [Departures]: the claim table already ignores a
 * second `Depart`, and a lingering presence entry would otherwise re-propose it on every check. A
 * proposal that fails is logged and not remembered, so the next lost link or check tries again;
 * for the boot check, that is the next [CHECK_EVERY], while the holder is still unseen. Each
 * proposal is logged.
 *
 * It decides; [depart] commits. `BeeHost` passes `ClaimsVoter::depart`, for [holders] the agents
 * holding a claim in the committed table, for [postings] the board's presence slots as it changes,
 * and for [presence] each poster's newest entry, however old (`hostDepartures`): a crashed agent's
 * entry has aged out of the live roster by the time the voter drops its node.
 *
 * The boot check compares a slot's clock only with that same slot's earlier clock, so no peer's
 * wall clock is compared with the host's. What it can't tell apart: two surviving peers that hold
 * different versions of a dead agent's entry, the older pushed first. That reads as an advance.
 */
class Departures(
    private val events: Flow<PartitionEvent>,
    private val presence: suspend () -> List<PresenceEntry>,
    private val postings: Flow<EphemeralMap<PresenceEntry>>,
    private val holders: suspend () -> Set<PeerIdentity>,
    private val depart: suspend (PeerIdentity, DepartReason) -> Unit,
    private val quietAfter: Duration = QUIET_AFTER,
    private val clock: () -> Instant,
) {
    init {
        require(quietAfter.isPositive()) { "quietAfter must be positive, was $quietAfter" }
    }

    private val mutex = Mutex()
    private val proposed = mutableSetOf<PeerIdentity>()
    private val posted = PostedSinceBoot()

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
            // Watched from boot until the boot check is done: after it, no one reads [posted].
            val watching = launch(CoroutineName("departures-postings")) {
                postings.collect { slots -> mutex.withLock { posted.observe(slots) } }
            }
            delay(BOOT_GRACE)
            departUnseen()
            watching.cancel()
        }
        return job
    }

    /** The boot check: one pass, then again each [CHECK_EVERY] only for a holder whose proposal failed. */
    private suspend fun departUnseen() {
        var pending = unseenHolders()
        log.info { "departures: boot grace of $BOOT_GRACE over; claim holders unseen since boot: ${pending.ifEmpty { "none" }}" }
        while (pending.isNotEmpty()) {
            pending = pending.filterNot { propose(it, DepartReason.Crashed) }.toSet()
            if (pending.isEmpty()) return
            delay(CHECK_EVERY)
            pending = unseenHolders() intersect pending
        }
    }

    private suspend fun unseenHolders(): Set<PeerIdentity> {
        val seen = mutex.withLock { posted.identities() }
        return holders().filter { it.kind == PeerKind.Agent && it !in seen }.toSet()
    }

    private suspend fun lostLink(node: NodeId) {
        newestAgentEntries().filter { it.claimsNode == node }.forEach { propose(it.identity, DepartReason.Crashed) }
    }

    private suspend fun departQuiet() {
        val now = clock()
        newestAgentEntries().filter { !it.waiting && now - it.lastActedAt >= quietAfter }
            .forEach { propose(it.identity, DepartReason.Quiet) }
    }

    /** Each agent's newest entry by `lastActedAt`, whichever slot it posted from. */
    private suspend fun newestAgentEntries(): List<PresenceEntry> =
        presence().filter { it.identity.kind == PeerKind.Agent }.groupBy { it.identity }.values.map { entries -> entries.maxBy { it.lastActedAt } }

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

    /**
     * Who has posted since this boot began: an identity one of whose slots advanced past the clock
     * it showed when first observed. A slot's first observation proves nothing, since a surviving
     * peer may be pushing back an entry the poster made before the host went down.
     */
    private class PostedSinceBoot {
        private val firstSeen = mutableMapOf<ReplicaId, Long>()
        private val advanced = mutableSetOf<PeerIdentity>()

        fun observe(slots: EphemeralMap<PresenceEntry>) {
            slots.entries.forEach { (replica, entry) ->
                val first = firstSeen.getOrPut(replica) { entry.clock }
                val value = entry.value
                if (value != null && entry.clock > first) advanced += value.identity
            }
        }

        fun identities(): Set<PeerIdentity> = advanced.toSet()
    }

    companion object {
        /** How often the quiet clock is read: a quiet agent departs within this of [quietAfter]. */
        val CHECK_EVERY: Duration = 30.seconds

        /** How long an agent may go without acting, and not waiting, before it departs `Quiet`. */
        val QUIET_AFTER: Duration = 20.minutes

        /**
         * How long after boot a claim holder has to post before it is departed (bee#44).
         *
         * A live bridge posts well inside it. Its board link redials at most 5 s apart, with a
         * 5 s dial timeout, and its entry is on the board it pushes on reconnect and re-posted
         * every [us.tractat.bee.core.Presence.EVERY] (5 s). So its entry reaches the host within
         * about 15 s of the host taking connections and advances by about 20 s: the grace is
         * three times that. Twice the
         * [ClaimsVoter.LEARNER_GRACE], it also outlasts the voter's removal of the nodes it
         * restored, so the lost-link rule has had its turn first. The cost of the margin is that a
         * dead holder's claims stay held for a minute after boot.
         */
        val BOOT_GRACE: Duration = ClaimsVoter.LEARNER_GRACE * 2
    }
}
