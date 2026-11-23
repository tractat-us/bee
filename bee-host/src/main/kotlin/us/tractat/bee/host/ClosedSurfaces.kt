package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.ClosedSurfaces")

/**
 * Removes from the board each surface closed for [keptFor] (bee#274). Invariant: **the host removes
 * a surface only once [clock] says its close is [keptFor] old; an open surface, or one closed more
 * recently, stays, and reopens as before.** The host is the only replica that removes: the check
 * and the removal run in one `mutate`, against the host's board as it is then
 * ([us.tractat.bee.core.BoardState.compactClosed]), so a reopen the host has already merged is
 * never removed.
 *
 * The check runs on start and every [every] after, so a surface goes at most [every] past its
 * grace period. Nothing is kept between checks: a host that was down simply removes on its first.
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

    /** Checks now and every [every] after, until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("closed-surfaces")) {
        while (true) {
            compact()
            delay(every)
        }
    }

    private fun compact() {
        val cutoff = clock() - keptFor
        val due = relay.state.value.closedBy(cutoff)
        if (due.isEmpty()) return
        relay.mutate { it.compactClosed(relay.replica, cutoff) }
        log.info { "Removed ${due.size} surfaces closed for $keptFor: ${due.sorted().joinToString()}" }
    }

    companion object {
        /** How long a closed surface stays on the board before the host removes it (Iain, 2026-11-23). */
        val KEPT_FOR: Duration = 1.days

        /** The longest a surface stays past [KEPT_FOR]. */
        val CHECK_EVERY: Duration = 1.hours
    }
}
