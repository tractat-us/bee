package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

/**
 * What a claim is about: an action record (`record:<replica>:<seq>`) or a card
 * (`card:<surfaceId>/<cardId>`). Opaque to the claim table.
 */
@Serializable
@JvmInline
value class ClaimTarget(val value: String) {
    init {
        require(value.startsWith("record:") || value.startsWith("card:")) { "Not a claim target: $value" }
    }

    override fun toString(): String = value
}

/**
 * A fencing token: the `(term, index)` of the log entry that granted a claim. A command that
 * names a token other than the target's current one is stale and applies as rejected.
 */
@Serializable
data class ClaimToken(val term: Long, val index: Long)

/**
 * One claim proposal's identity, drawn at random by its proposer. kuilt's `LogEntry.dedupKey` can't
 * serve: `ClusterClient.propose` draws the serial inside the call and hands it back only on success,
 * so a proposal that timed out has no key its proposer knows.
 */
@Serializable
@JvmInline
value class ProposalId(val value: Long)

/**
 * One command in the claims log. Every command is idempotent under replay: the table it folds
 * into rejects a second application of the same command, because the first changed the state the
 * second is checked against.
 */
@Serializable
sealed interface ClaimCommand {
    /**
     * Take [target]. The first committed claim of a free target wins. [proposal] names this one
     * proposal, so its proposer can tell it from another claim of the same target by the same
     * identity: a person's tabs all claim as one `human:` identity (spec §5.1). The table ignores it.
     */
    @Serializable
    @SerialName("claim")
    data class Claim(
        val target: ClaimTarget,
        @Serializable(PeerIdentityAsString::class) val by: PeerIdentity,
        val proposal: ProposalId,
    ) : ClaimCommand

    /**
     * Give [target] back. The holder releases with its current [token]. A release with no token
     * is any participant's decision on an orphaned target.
     */
    @Serializable
    @SerialName("release")
    data class Release(
        val target: ClaimTarget,
        @Serializable(PeerIdentityAsString::class) val by: PeerIdentity,
        val token: ClaimToken?,
    ) : ClaimCommand

    /** A participant's decision that an orphaned [target] goes [to] someone, with a new token. */
    @Serializable
    @SerialName("reassign")
    data class Reassign(
        val target: ClaimTarget,
        @Serializable(PeerIdentityAsString::class) val to: PeerIdentity,
        @Serializable(PeerIdentityAsString::class) val by: PeerIdentity,
    ) : ClaimCommand

    /** The holder finished [target], fenced by its current [token]. */
    @Serializable
    @SerialName("complete")
    data class Complete(
        val target: ClaimTarget,
        @Serializable(PeerIdentityAsString::class) val by: PeerIdentity,
        val token: ClaimToken,
    ) : ClaimCommand

    /**
     * The host retired agent [identity]: its held claims are orphaned and its later commands
     * rejected. Only agents depart (spec §6.4), so a `Depart` of a human applies as rejected.
     */
    @Serializable
    @SerialName("depart")
    data class Depart(
        @Serializable(PeerIdentityAsString::class) val identity: PeerIdentity,
        val reason: DepartReason,
    ) : ClaimCommand

    companion object {
        private val json = Json { classDiscriminator = "type" }

        fun encode(command: ClaimCommand): ByteArray = json.encodeToString(serializer(), command).encodeToByteArray()

        /** Reads what [encode] writes; throws [kotlinx.serialization.SerializationException] on anything else. */
        fun decode(bytes: ByteArray): ClaimCommand = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}
