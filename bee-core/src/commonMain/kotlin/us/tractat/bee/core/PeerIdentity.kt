package us.tractat.bee.core

import kotlin.random.Random

/**
 * Whether a peer is a person, a coding agent, or the host.
 *
 * A kind is a label: what a peer may do never rests on it (spec §5.7, bee#68). [Host] is what the
 * host opens its own surfaces as, so the board shows them as the host's (bee#71).
 */
enum class PeerKind { Human, Agent, Host }

/**
 * Who a peer is, above any connection or CRDT author.
 *
 * Agents are `agent:<name>#<instance>`, with a fresh 8-hex [instance] per bridge identity.
 * Humans are `human:<name>`, one identity across tabs and devices, and have no instance.
 * The host is `host:<name>`, with no instance either.
 */
data class PeerIdentity(val kind: PeerKind, val name: String, val instance: String) {
    init {
        require(name.isNotEmpty()) { "A peer name is empty." }
        require(':' !in name && '#' !in name) { "A peer name holds ':' or '#': $name" }
        when (kind) {
            PeerKind.Agent -> require(INSTANCE.matches(instance)) { "An agent instance is not 8 lowercase hex: $instance" }
            PeerKind.Human -> require(instance.isEmpty()) { "A human identity has an instance: $instance" }
            PeerKind.Host -> require(instance.isEmpty()) { "A host identity has an instance: $instance" }
        }
    }

    override fun toString(): String = when (kind) {
        PeerKind.Agent -> "agent:$name#$instance"
        PeerKind.Human -> "human:$name"
        PeerKind.Host -> "host:$name"
    }

    companion object {
        private val INSTANCE = Regex("[0-9a-f]{8}")
        private val AGENT = Regex("agent:([^:#]+)#([0-9a-f]{8})")
        private val HUMAN = Regex("human:([^:#]+)")
        private val HOST = Regex("host:([^:#]+)")

        /**
         * How bee wrote the host before it had its own kind: an agent named for it. Snapshots from
         * then still hold it, as who opened a surface, so it reads as the host.
         */
        private const val OLD_HOST = "agent:bee-host#00000000"

        /** A fresh agent identity named [name], its instance drawn from [random]. */
        fun agent(name: String, random: Random): PeerIdentity =
            PeerIdentity(PeerKind.Agent, name, random.nextInt().toUInt().toString(16).padStart(8, '0'))

        fun human(name: String): PeerIdentity = PeerIdentity(PeerKind.Human, name, "")

        fun host(name: String): PeerIdentity = PeerIdentity(PeerKind.Host, name, "")

        /** Reads the form [toString] writes, and the host's old form; throws [IllegalArgumentException] on anything else. */
        fun parse(s: String): PeerIdentity {
            if (s == OLD_HOST) return host("bee-host")
            AGENT.matchEntire(s)?.let { return PeerIdentity(PeerKind.Agent, it.groupValues[1], it.groupValues[2]) }
            HUMAN.matchEntire(s)?.let { return human(it.groupValues[1]) }
            HOST.matchEntire(s)?.let { return host(it.groupValues[1]) }
            throw IllegalArgumentException("Not a peer identity: $s")
        }
    }
}

/** Why the host retired an agent identity (a committed `Depart`). */
enum class DepartReason { Crashed, Quiet, Cleared }
