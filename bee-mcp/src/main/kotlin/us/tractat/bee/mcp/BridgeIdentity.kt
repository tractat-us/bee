package us.tractat.bee.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import kotlin.random.Random

/**
 * The bridge's peer identity, which outlives no `/clear` (spec §5.2).
 *
 * The bridge process survives `/clear`, but the agent behind it has lost its context, so its
 * identity retires and a fresh one takes its place. [onRetire] reports the old identity, and
 * Task 7 wires it to a `Depart` through Raft.
 *
 * It starts as [start], having held [before] too: a fresh identity for a new bridge, or the ones
 * an HTTP session held before its host restarted ([KeptIdentities], bee#126).
 */
class BridgeIdentity(
    private val name: String,
    private val random: Random,
    start: PeerIdentity = PeerIdentity.agent(name, random),
    before: Set<PeerIdentity> = emptySet(),
    private val onRetire: suspend (PeerIdentity, DepartReason) -> Unit,
) {
    private val mutex = Mutex()
    private val identity = MutableStateFlow(start)

    /** The identity the bridge acts as now. */
    val current: StateFlow<PeerIdentity> = identity.asStateFlow()

    /**
     * Every identity this bridge process has acted as, [current] among them: the surfaces any of
     * them opened are still this process's (spec §5.2, a board-wide wait).
     */
    @Volatile var held: Set<PeerIdentity> = before + start
        private set

    /**
     * Mints a fresh identity, then reports the old one with [reason], and returns the old one.
     *
     * The fresh identity is in place before [onRetire] runs, so a report that fails (it propagates)
     * never leaves the bridge acting as the identity it meant to retire.
     */
    suspend fun retire(reason: DepartReason): PeerIdentity = mutex.withLock {
        val old = identity.value
        val fresh = generateSequence { PeerIdentity.agent(name, random) }.first { it != old }
        // Held before it's current, so the current identity is always among those held.
        held = held + fresh
        identity.value = fresh
        onRetire(old, reason)
        old
    }
}
