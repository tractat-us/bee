package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry

/** Where one claim target stands. A target never seen is [Free]. */
@Serializable
sealed interface ClaimStatus {
    @Serializable
    @SerialName("free")
    data object Free : ClaimStatus

    @Serializable
    @SerialName("held")
    data class Held(@Serializable(PeerIdentityAsString::class) val holder: PeerIdentity, val token: ClaimToken) : ClaimStatus

    /** Still held by a retired [holder], and flagged. Nothing reassigns it but a participant. */
    @Serializable
    @SerialName("orphaned")
    data class Orphaned(@Serializable(PeerIdentityAsString::class) val holder: PeerIdentity, val token: ClaimToken) : ClaimStatus

    @Serializable
    @SerialName("done")
    data class Done(@Serializable(PeerIdentityAsString::class) val by: PeerIdentity) : ClaimStatus
}

/**
 * The claims state machine over the committed log: every peer that folds the same committed
 * stream holds the same table, and each target has at most one holder at a time.
 *
 * Immutable: [fold] returns the next table. Rules (spec §6.3, §6.4):
 * - The first committed [ClaimCommand.Claim] of a [ClaimStatus.Free] target wins, with the
 *   entry's `(term, index)` as its token; any other claim applies as rejected.
 * - [ClaimCommand.Release] and [ClaimCommand.Complete] need the holder and its current token. A
 *   release with no token applies only to an [ClaimStatus.Orphaned] target.
 * - [ClaimCommand.Reassign] applies only to an orphaned target, and mints a new token.
 * - [ClaimCommand.Depart] orphans every claim the identity holds, and every later command by a
 *   retired identity applies as rejected. Only agents depart (§6.4): a `Depart` of a human applies
 *   as rejected. The table can't check who proposed a `Depart`, so it trusts the log on that (the
 *   spike 4 notes say why).
 *
 * A rejected command still advances [appliedIndex]: every committed index is accounted for.
 *
 * **It keeps each hold's provenance** ([reassigned]): a hold a `Reassign` granted is told from one
 * its holder won, so a bridge's sweep, which gives back every hold of its own that no caller of it
 * received, keeps a reassigned one (bee#31, Task 11).
 *
 * **It relies on each proposal reaching the log at most once.** It folds every entry, so a second
 * entry carrying the same `Claim`, committed after that claim's `Release`, wins the target again
 * under a token nobody holds. No duplicate arrives on kuilt `0.7.0-dev.1692` within one incarnation,
 * but kuilt's forwarding contract allows one (`ClientIdentity.Auto`: the consumer's
 * `ClientSessionTable` skips it). Production needs kuilt's `ClientSessionTable` folded on
 * `entry.dedupKey`, with a session-close hook so it stays bounded. That hook needs a signal that a
 * session has ended (tractat-us/kuilt#3129). Tracked as bee#23.
 */
class ClaimTable private constructor(
    private val claims: Map<ClaimTarget, ClaimStatus>,
    private val departed: Map<PeerIdentity, DepartReason>,
    /** The token each reassigned target was granted under, kept until the target leaves that hold. */
    private val grants: Map<ClaimTarget, ClaimToken>,
    val appliedIndex: Long,
) {
    fun status(target: ClaimTarget): ClaimStatus = claims[target] ?: ClaimStatus.Free

    /** Every target [identity] holds, orphaned ones included. */
    fun heldBy(identity: PeerIdentity): Set<ClaimTarget> =
        claims.filterValues { it.holder() == identity }.keys

    fun retired(identity: PeerIdentity): Boolean = identity in departed

    /**
     * Whether [target] is held under a token a [ClaimCommand.Reassign] minted: a participant's
     * decision handed it to its holder, who never claimed it. False for a hold its holder won.
     */
    fun reassigned(target: ClaimTarget): Boolean {
        val held = status(target) as? ClaimStatus.Held ?: return false
        return grants[target] == held.token
    }

    /** Why [identity] was retired, or null if it wasn't. */
    fun departReason(identity: PeerIdentity): DepartReason? = departed[identity]

    /** Every target that isn't free, for the board's claims mirror. */
    val targets: Map<ClaimTarget, ClaimStatus> get() = claims

    /** Every retired identity, for the board's departure list. */
    val retiredIdentities: Map<PeerIdentity, DepartReason> get() = departed

    /** The table after [committed]. An index at or below [appliedIndex] changes nothing. */
    fun fold(committed: Committed): ClaimTable = applied(committed).table

    /**
     * The table after [committed], with the command it applied: decoded once, for a folder that
     * acts on its own commands. The command is null for anything but a new entry, and for an entry
     * that doesn't decode, which applies as rejected.
     */
    fun applied(committed: Committed): Applied = when (committed) {
        is Committed.Entry -> if (committed.entry.index <= appliedIndex) Applied(this, null) else apply(committed.entry)
        is Committed.Internal -> Applied(if (committed.index <= appliedIndex) this else copy(appliedIndex = committed.index), null)
        is Committed.Install -> Applied(decode(committed.snapshot.state), null)
    }

    /** A fold's result: the next [table], and the [command] that entry carried, if it decoded. */
    data class Applied(val table: ClaimTable, val command: ClaimCommand?)

    fun encode(): ByteArray = json.encodeToString(Encoded.serializer(), toEncoded()).encodeToByteArray()

    private fun apply(entry: LogEntry): Applied {
        val command = try {
            ClaimCommand.decode(entry.command)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val token = ClaimToken(entry.term, entry.index)
        val next = command?.let { applyCommand(it, token) } ?: this
        return Applied(next.copy(appliedIndex = entry.index), command)
    }

    private fun applyCommand(command: ClaimCommand, token: ClaimToken): ClaimTable? = when (command) {
        is ClaimCommand.Claim -> claim(command, token)
        is ClaimCommand.Release -> release(command)
        is ClaimCommand.Reassign -> reassign(command, token)
        is ClaimCommand.Complete -> complete(command)
        is ClaimCommand.Depart -> depart(command)
    }

    private fun claim(c: ClaimCommand.Claim, token: ClaimToken): ClaimTable? =
        if (!retired(c.by) && status(c.target) == ClaimStatus.Free) set(c.target, ClaimStatus.Held(c.by, token)) else null

    private fun release(c: ClaimCommand.Release): ClaimTable? {
        if (retired(c.by)) return null
        val current = status(c.target)
        val allowed = when (c.token) {
            null -> current is ClaimStatus.Orphaned
            else -> current is ClaimStatus.Held && current.holder == c.by && current.token == c.token
        }
        return if (allowed) copy(claims = claims - c.target, grants = grants - c.target) else null
    }

    private fun reassign(c: ClaimCommand.Reassign, token: ClaimToken): ClaimTable? =
        if (!retired(c.by) && !retired(c.to) && status(c.target) is ClaimStatus.Orphaned) {
            set(c.target, ClaimStatus.Held(c.to, token)).copy(grants = grants + (c.target to token))
        } else {
            null
        }

    private fun complete(c: ClaimCommand.Complete): ClaimTable? {
        val current = status(c.target)
        // No retired check: a retired identity can't hold a Held target (Depart orphans it, and a
        // retired identity's claims and reassigns to it are rejected).
        val allowed = current is ClaimStatus.Held && current.holder == c.by && current.token == c.token
        return if (allowed) set(c.target, ClaimStatus.Done(c.by)).copy(grants = grants - c.target) else null
    }

    private fun depart(c: ClaimCommand.Depart): ClaimTable? {
        if (c.identity.kind != PeerKind.Agent || retired(c.identity)) return null
        val orphaned = claims.mapValues { (_, s) ->
            if (s is ClaimStatus.Held && s.holder == c.identity) ClaimStatus.Orphaned(s.holder, s.token) else s
        }
        val stillHeld = grants.filterKeys { orphaned[it] is ClaimStatus.Held }
        return copy(claims = orphaned, departed = departed + (c.identity to c.reason), grants = stillHeld)
    }

    private fun set(target: ClaimTarget, status: ClaimStatus) = copy(claims = claims + (target to status))

    private fun copy(
        claims: Map<ClaimTarget, ClaimStatus> = this.claims,
        departed: Map<PeerIdentity, DepartReason> = this.departed,
        grants: Map<ClaimTarget, ClaimToken> = this.grants,
        appliedIndex: Long = this.appliedIndex,
    ) = ClaimTable(claims, departed, grants, appliedIndex)

    private fun toEncoded() = Encoded(
        appliedIndex = appliedIndex,
        claims = claims.map { (t, s) -> EncodedClaim(t, s) },
        departed = departed.map { (i, r) -> EncodedDeparture(i, r) },
        grants = grants.map { (t, token) -> EncodedGrant(t, token) },
    )

    override fun equals(other: Any?): Boolean =
        other is ClaimTable && claims == other.claims && departed == other.departed && grants == other.grants &&
            appliedIndex == other.appliedIndex

    override fun hashCode(): Int = ((claims.hashCode() * 31 + departed.hashCode()) * 31 + grants.hashCode()) * 31 + appliedIndex.hashCode()

    override fun toString(): String = "ClaimTable(appliedIndex=$appliedIndex, claims=$claims, departed=$departed, reassigned=$grants)"

    @Serializable
    private data class Encoded(
        val appliedIndex: Long,
        val claims: List<EncodedClaim>,
        val departed: List<EncodedDeparture>,
        // Absent from a table encoded before Task 11, which had no reassigns to keep.
        val grants: List<EncodedGrant> = emptyList(),
    )

    @Serializable
    private data class EncodedClaim(val target: ClaimTarget, val status: ClaimStatus)

    @Serializable
    private data class EncodedGrant(val target: ClaimTarget, val token: ClaimToken)

    @Serializable
    private data class EncodedDeparture(@Serializable(PeerIdentityAsString::class) val identity: PeerIdentity, val reason: DepartReason)

    companion object {
        val EMPTY: ClaimTable = ClaimTable(emptyMap(), emptyMap(), emptyMap(), 0L)

        private val json = Json { classDiscriminator = "type" }

        /** Reads what [encode] writes. */
        fun decode(bytes: ByteArray): ClaimTable {
            val e = json.decodeFromString(Encoded.serializer(), bytes.decodeToString())
            return ClaimTable(
                claims = e.claims.associate { it.target to it.status },
                departed = e.departed.associate { it.identity to it.reason },
                grants = e.grants.associate { it.target to it.token },
                appliedIndex = e.appliedIndex,
            )
        }
    }
}

private fun ClaimStatus.holder(): PeerIdentity? = when (this) {
    is ClaimStatus.Held -> holder
    is ClaimStatus.Orphaned -> holder
    ClaimStatus.Free, is ClaimStatus.Done -> null
}
