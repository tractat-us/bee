package us.tractat.bee.core

import kotlin.random.Random

/** Whether a peer is a person or a coding agent. */
enum class PeerKind { Human, Agent }

/**
 * Who a peer is, above any connection or CRDT author.
 *
 * Agents are `agent:<name>#<instance>`, with a fresh 8-hex [instance] per bridge identity.
 * Humans are `human:<name>`, one identity across tabs and devices, and have no instance.
 */
data class PeerIdentity(val kind: PeerKind, val name: String, val instance: String) {
    init {
        require(name.isNotEmpty()) { "A peer name is empty." }
        require(':' !in name && '#' !in name) { "A peer name holds ':' or '#': $name" }
        when (kind) {
            PeerKind.Agent -> require(INSTANCE.matches(instance)) { "An agent instance is not 8 lowercase hex: $instance" }
            PeerKind.Human -> require(instance.isEmpty()) { "A human identity has an instance: $instance" }
        }
    }

    override fun toString(): String = when (kind) {
        PeerKind.Agent -> "agent:$name#$instance"
        PeerKind.Human -> "human:$name"
    }

    companion object {
        private val INSTANCE = Regex("[0-9a-f]{8}")
        private val AGENT = Regex("agent:([^:#]+)#([0-9a-f]{8})")
        private val HUMAN = Regex("human:([^:#]+)")

        /** A fresh agent identity named [name], its instance drawn from [random]. */
        fun agent(name: String, random: Random): PeerIdentity =
            PeerIdentity(PeerKind.Agent, name, random.nextInt().toUInt().toString(16).padStart(8, '0'))

        fun human(name: String): PeerIdentity = PeerIdentity(PeerKind.Human, name, "")

        /** Reads the form [toString] writes; throws [IllegalArgumentException] on anything else. */
        fun parse(s: String): PeerIdentity {
            AGENT.matchEntire(s)?.let { return PeerIdentity(PeerKind.Agent, it.groupValues[1], it.groupValues[2]) }
            HUMAN.matchEntire(s)?.let { return human(it.groupValues[1]) }
            throw IllegalArgumentException("Not a peer identity: $s")
        }
    }
}

/** Why the host retired an agent identity (a committed `Depart`). */
enum class DepartReason { Crashed, Quiet, Cleared }
