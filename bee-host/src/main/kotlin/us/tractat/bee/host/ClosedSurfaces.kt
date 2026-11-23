package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.OrphanDecision
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.ClosedSurfaces")

/**
 * Removes from the board each surface closed for [keptFor] (bee#274). Invariant: **the host
 * removes a surface only once both its `closedAt` and the moment this host first saw it closed are
 * [keptFor] old on [clock]; an open surface, one closed more recently, and an orphan decision whose
 * target is still orphaned all stay.**
 *
 * - **Grace runs from what the host saw.** A close made offline and delivered late, or stamped by a
 *   slow clock, still gets its full grace period. What the host first saw isn't saved: a boot
 *   counts as the first sight of everything already closed, so the worst case is one more period
 *   after a restart, and nothing is removed at boot. A peer that reopened a surface while the host
 *   was down has a whole period to reconnect.
 * - **The host never fights a close of an orphan decision** ([OrphanDecisions]): a closed
 *   `orphan-<target>` stays while the mirror shows its target orphaned, since removing it would only
 *   have [OrphanDecisions] post it again. Once the target isn't orphaned, it goes like any other.
 * - **The removal is checked again in the host's `mutate`** ([BoardState.compactClosed]), so a
 *   reopen the host has merged since the check keeps the surface.
 *
 * Checks run on start and every [every] after, so a surface goes at most [every] past its grace
 * period, and a close is first seen at most [every] after it arrives. Only the checking coroutine
 * touches [firstSeen].
 */
class ClosedSurfaces(
    private val relay: BoardRelay,
    private val keptFor: Duration,
    private val clock: () -> Instant,
) {
    init {
        require(keptFor.isPositive()) { "keptFor must be positive, was $keptFor" }
    }

    private val every: Duration = minOf(CHECK_EVERY, keptFor / 4)

    /** Each surface closed at the last check, with when a check first saw it closed. */
    private val firstSeen = mutableMapOf<String, Instant>()

    /** Checks now and every [every] after, until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("closed-surfaces")) {
        while (true) {
            check()
            delay(every)
        }
    }

    private fun check() {
        val now = clock()
        val state = relay.state.value
        val closed = state.closedSurfaces().toSet()
        firstSeen.keys.retainAll(closed)
        closed.forEach { firstSeen.getOrPut(it) { now } }
        val cutoff = now - keptFor
        val due = state.closedBy(cutoff).filter { firstSeen.getValue(it) <= cutoff && !decidesAnOrphan(state, it) }.toSet()
        if (due.isEmpty()) return
        relay.mutate { it.compactClosed(relay.replica, due, cutoff) }
        log.info { "Removed ${due.size} surfaces closed for $keptFor: ${due.sorted().joinToString()}" }
    }

    /** Whether [id] is the decision of a target the mirror still shows orphaned. */
    private fun decidesAnOrphan(state: BoardState, id: String): Boolean =
        OrphanDecision.targetOf(id)?.let { state.claimView(it)?.status == ClaimView.ORPHANED } == true

    companion object {
        /** How long a closed surface stays on the board before the host removes it (Iain, 2026-11-23). */
        val KEPT_FOR: Duration = 1.days

        /** The longest a surface stays past [KEPT_FOR]. */
        val CHECK_EVERY: Duration = 1.hours
    }
}
