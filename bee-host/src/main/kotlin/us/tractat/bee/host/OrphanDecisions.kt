package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.bee.a2ui.NOTIONS_CATALOG_ID
import us.tractat.bee.a2ui.toSurfaceView
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimTarget
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.OrphanDecision
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.SurfaceMeta
import us.tractat.kuilt.crdt.Patch
import kotlin.time.Instant

private val log = KotlinLogging.logger("us.tractat.bee.host.OrphanDecisions")

/**
 * Orphan decisions on the board (spec §6.4): for each orphaned claim, the host opens
 * `orphan-<target>`, a `Poll` asking whether to reassign it to a present peer, release it, or
 * wait ([OrphanDecision] has its shape). Invariant: **the host writes the decision and proposes
 * nothing.** The answerer's own client proposes a `Reassign` or a `Release` ([us.tractat.bee.core.OrphanAnswers]),
 * "Wait" proposes nothing, and this class holds no claims client at all: the host's one proposal
 * stays `Depart` ([Departures]).
 *
 * It reads the board's claims mirror, never the voter's table, so a voter re-folding its log on
 * boot can't close a decision it is about to reopen: each mirrored view carries its log index, and
 * a replayed one never wins over the newest (`BoardState.mirrorClaims`).
 *
 * - **One surface per target.** It is opened for one orphaning, named by its token (`/token`). A
 *   target orphaned again, after a reassign to an agent that then departed, gets a new question
 *   revision on the same surface, reopened if it was closed.
 * - **A decision closes once its target isn't orphaned**, and keeps its content and records, in
 *   the history strip. A person who clears the board closes an open decision too; it stays closed
 *   until the target is orphaned again, so the host never fights a clear.
 * - **Options only grow.** They're an append-only array (A12): a peer who arrives later is
 *   appended, and one who leaves keeps its option, whose `Reassign` the table rejects once that
 *   peer is retired.
 *
 * @param present the identities attached now: `LivePresence.live()` in production.
 */
class OrphanDecisions(
    private val relay: BoardRelay,
    private val present: suspend () -> List<PeerIdentity>,
    private val clock: () -> Instant,
) {
    /** Keeps the decisions in step with the board until [scope] ends. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        relay.state.collect { state ->
            try {
                reconcile(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (bug: IllegalArgumentException) {
                // A surface someone else wrote under an orphan id, in a shape this can't extend.
                log.error(bug) { "orphan decisions: a decision surface couldn't be written" }
            }
        }
    }

    private suspend fun reconcile(state: BoardState) {
        val orphans = state.claims.entries.filterValues { it.status == ClaimView.ORPHANED }
        if (orphans.isNotEmpty()) {
            val retired = state.departures().map { it.identity }.toSet()
            val peers = present().filter { it !in retired && it != JoinRequestSurface.HOST }.distinct()
            orphans.forEach { (key, view) -> decide(ClaimTarget(key), view, peers) }
        }
        relay.state.value.openSurfaces()
            .mapNotNull { id -> OrphanDecision.targetOf(id)?.let { id to it } }
            .filter { (_, target) -> relay.state.value.claimView(target)?.status != ClaimView.ORPHANED }
            .forEach { (id, _) -> relay.mutate { it.closeSurface(relay.replica, id, clock()) } }
    }

    /** Opens, revises or extends [target]'s decision, from the board as it is now. */
    private fun decide(target: ClaimTarget, view: ClaimView, peers: List<PeerIdentity>) {
        val holder = PeerIdentity.parse(requireNotNull(view.holder) { "an orphaned view names no holder: $view" })
        val token = requireNotNull(view.token) { "an orphaned view names no token: $view" }
        val id = OrphanDecision.surfaceId(target)
        val offered = options(peers.filter { it != holder })
        val state = relay.state.value
        val doc = state.surfaceDoc(id)
        val writes = when {
            doc == null -> opening(id, target, holder, token, offered)
            else -> {
                val data = doc.toSurfaceView(id).data
                val revision = if (data.string(TOKEN) != token) reorphaning(id, holder, token, state) else emptyList()
                revision + appending(id, offered, data)
            }
        }
        // Only the host writes a decision, and this collector runs one state at a time, so the
        // writes planned from the state just read still fit the state they land on.
        if (writes.isNotEmpty()) relay.mutate { threaded(it, writes) }
    }

    private fun opening(id: String, target: ClaimTarget, holder: PeerIdentity, token: String, offered: List<JsonObject>): List<Write> {
        val meta = SurfaceMeta(TITLE, NOTIONS_CATALOG_ID, JoinRequestSurface.HOST, clock())
        val data = buildJsonObject {
            put(TARGET, target.value)
            put(HOLDER, holder.toString())
            put(TOKEN, token)
            putJsonObject(ANSWERS) {}
        }
        return listOf<Write>(
            { it.openSurface(relay.replica, id, meta, COMPONENTS, data) },
            { it.appendEntry(relay.replica, id, "/$QUESTION", JsonPrimitive(question(target, holder))) },
        ) + offered.map { option -> { s: BoardState -> s.appendEntry(relay.replica, id, "/$OPTIONS", option) } }
    }

    /** The target was orphaned again: a new question, the new orphaning's token, and the decision open. */
    private fun reorphaning(id: String, holder: PeerIdentity, token: String, state: BoardState): List<Write> {
        val target = requireNotNull(OrphanDecision.targetOf(id))
        val reopen: List<Write> = if (id in state.openSurfaces()) emptyList() else listOf({ it.reopenSurface(relay.replica, id) })
        return reopen + listOf<Write>(
            { it.updateData(relay.replica, id, "/$HOLDER", JsonPrimitive(holder.toString())) },
            { it.updateData(relay.replica, id, "/$TOKEN", JsonPrimitive(token)) },
            { it.appendEntry(relay.replica, id, "/$QUESTION", JsonPrimitive(question(target, holder))) },
        )
    }

    /** The options [offered] that [data] doesn't hold yet, appended. */
    private fun appending(id: String, offered: List<JsonObject>, data: JsonObject): List<Write> {
        val held = (data[OPTIONS] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string(OPTION_ID) }.toSet()
        return offered.filter { it.string(OPTION_ID) !in held }.map { option -> { s: BoardState -> s.appendEntry(relay.replica, id, "/$OPTIONS", option) } }
    }

    private fun options(peers: List<PeerIdentity>): List<JsonObject> =
        peers.map { option(OrphanDecision.reassignOption(it), "Reassign to $it") } +
            option(OrphanDecision.RELEASE, "Release it") +
            option(OrphanDecision.WAIT, "Wait")

    private fun option(id: String, label: String) = buildJsonObject {
        put(OPTION_ID, id)
        put(OPTION_LABEL, label)
    }

    private fun question(target: ClaimTarget, holder: PeerIdentity) =
        "$holder left $target held. Reassign it, release it, or wait?"

    private fun threaded(state: BoardState, writes: List<Write>): Patch<BoardState> {
        val (_, delta) = writes.fold(state to BoardState.EMPTY) { (s, d), write ->
            val step = write(s).delta
            s.piece(step) to d.piece(step)
        }
        return Patch(delta)
    }

    private companion object {
        const val TITLE = "An orphaned claim needs a decision"
        const val TARGET = "target"
        const val HOLDER = "holder"
        const val TOKEN = "token"
        const val QUESTION = "question"
        const val OPTIONS = "options"
        const val ANSWERS = "answers"
        const val OPTION_ID = "id"
        const val OPTION_LABEL = "label"

        /** A Notions `Poll` bound to the decision's data (plan Task 11's `Poll` interface). */
        val COMPONENTS: List<JsonObject> = listOf(
            buildJsonObject {
                put("id", "root")
                put("component", "Poll")
                putJsonObject(QUESTION) { put("path", "/$QUESTION") }
                putJsonObject(OPTIONS) { put("path", "/$OPTIONS") }
                putJsonObject(ANSWERS) { put("path", "/$ANSWERS") }
            },
        )

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

private typealias Write = (BoardState) -> Patch<BoardState>
