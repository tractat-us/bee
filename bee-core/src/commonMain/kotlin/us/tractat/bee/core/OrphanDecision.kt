package us.tractat.bee.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The decision an orphaned claim asks for (spec §6.4), as the host writes it and an answering
 * client reads it. Surface `orphan-<target>` is a Notions `Poll`:
 *
 * - `/question` is an append-only array of revisions, each a string; the last one shows.
 * - `/options` is an append-only array of `{"id", "label"}` objects: "Reassign to <peer>" for
 *   each present peer ([reassignOption]), [RELEASE] and [WAIT].
 * - `/rounds/<token>/answers` is an object keyed by `PeerIdentity.toString()`, each the option id
 *   that peer chose, for the orphaning that token names. The `Poll` binds the current one.
 * - `/target`, `/holder` and `/token` name the orphan the surface is open for now.
 *
 * An answer arrives as the action [ANSWER_ACTION] with context `{"option": <id>}` ([OPTION_KEY]).
 * The answerer's own client proposes what it chose ([OrphanAnswers]); the host proposes nothing.
 */
object OrphanDecision {
    const val PREFIX: String = "orphan-"
    const val ANSWER_ACTION: String = "answer"
    const val OPTION_KEY: String = "option"
    const val RELEASE: String = "release"
    const val WAIT: String = "wait"
    private const val REASSIGN = "reassign:"

    /** The decision surface for [target]. */
    fun surfaceId(target: ClaimTarget): String = PREFIX + target.value

    /** The target a decision surface is for, or null if [surfaceId] isn't one. */
    fun targetOf(surfaceId: String): ClaimTarget? {
        if (!surfaceId.startsWith(PREFIX)) return null
        return try {
            ClaimTarget(surfaceId.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The option id for reassigning the orphan [to] a peer. */
    fun reassignOption(to: PeerIdentity): String = REASSIGN + to

    /** What option id [option] decides, or null if it names no option. */
    fun choice(option: String): Choice? = when {
        option == RELEASE -> Choice.Release
        option == WAIT -> Choice.Wait
        option.startsWith(REASSIGN) -> try {
            Choice.Reassign(PeerIdentity.parse(option.removePrefix(REASSIGN)))
        } catch (_: IllegalArgumentException) {
            null
        }
        else -> null
    }

    /** One answer to an orphan decision. */
    sealed interface Choice {
        data class Reassign(val to: PeerIdentity) : Choice

        data object Release : Choice

        data object Wait : Choice
    }
}

/**
 * An answering client's half of an orphan decision: it proposes what its own [actor] chose, through
 * that client's claims. Invariant: **only the client whose person answered proposes, and only a
 * `Reassign` or a `Release`; "Wait" proposes nothing.** The claim table decides whether it applies:
 * a target no longer orphaned rejects both, so a second answer, or one from another tab, is harmless.
 *
 * A screen never has one (spec §6.4, A7): its page builds no claims client.
 *
 * @param claims the client's claims, or null while its claims link is down.
 */
class OrphanAnswers(private val claims: () -> ClaimsClient?, private val actor: PeerIdentity) {
    /** What an action came to. */
    sealed interface Outcome {
        /** Not an answer to an orphan decision. */
        data object NotADecision : Outcome

        /** "Wait": nothing was proposed, and the claim stays orphaned. */
        data object Waited : Outcome

        /** Proposed; [applied] is whether it committed and took effect. */
        data class Proposed(val choice: OrphanDecision.Choice, val applied: Boolean) : Outcome

        /** The claims link is down, so nothing was proposed. */
        data object Offline : Outcome
    }

    /** Acts on [actor]'s action [name] on [surfaceId], with [context], if it answers an orphan decision. */
    suspend fun answered(surfaceId: String, name: String, context: JsonObject): Outcome {
        val target = OrphanDecision.targetOf(surfaceId) ?: return Outcome.NotADecision
        if (name != OrphanDecision.ANSWER_ACTION) return Outcome.NotADecision
        val option = (context[OrphanDecision.OPTION_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val choice = option?.let(OrphanDecision::choice) ?: return Outcome.NotADecision
        if (choice == OrphanDecision.Choice.Wait) return Outcome.Waited
        val client = claims() ?: return Outcome.Offline
        val applied = when (choice) {
            is OrphanDecision.Choice.Reassign -> client.reassign(target, to = choice.to, by = actor) != null
            OrphanDecision.Choice.Release -> client.release(target, by = actor, token = null)
            OrphanDecision.Choice.Wait -> error("a wait proposes nothing")
        }
        return Outcome.Proposed(choice, applied)
    }
}
