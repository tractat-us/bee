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
 */
class BridgeIdentity(
    private val name: String,
    private val random: Random,
    private val onRetire: suspend (PeerIdentity, DepartReason) -> Unit,
) {
    private val mutex = Mutex()
    private val identity = MutableStateFlow(PeerIdentity.agent(name, random))

    /** The identity the bridge acts as now. */
    val current: StateFlow<PeerIdentity> = identity.asStateFlow()

    /**
     * Mints a fresh identity, then reports the old one with [reason], and returns the old one.
     *
     * The fresh identity is in place before [onRetire] runs, so a report that fails (it propagates)
     * never leaves the bridge acting as the identity it meant to retire.
     */
    suspend fun retire(reason: DepartReason): PeerIdentity = mutex.withLock {
        val old = identity.value
        identity.value = generateSequence { PeerIdentity.agent(name, random) }.first { it != old }
        onRetire(old, reason)
        old
    }
}
