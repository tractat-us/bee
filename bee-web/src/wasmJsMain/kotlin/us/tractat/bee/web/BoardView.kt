package us.tractat.bee.web

import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.RecordId

/** How a peer shows in the attached strip (spec §6.4). */
enum class PeerState {
    /** An agent waiting on the board for a person. */
    Waiting,

    /** An agent at work. */
    Acting,

    /** A person, or a room screen. */
    Here,

    /** An agent the host retired: crashed, quiet or cleared. */
    Departed,
}

/** One peer in the attached strip. [status] is its "working on…" line; [departed] says why it left. */
data class PeerView(val identity: PeerIdentity, val state: PeerState, val status: String?, val departed: DepartReason?)

/** A claim on something a card shows: held by [holder], or orphaned when its holder departed. */
data class ClaimBadge(val target: String, val holder: String, val orphaned: Boolean)

/**
 * One open surface as a card: its [title], who opened it, and that opener's "working on…" line while
 * it is attached ([openerStatus]), or why it left ([openerDeparted]). [claims] are the held and
 * orphaned claims on the surface's cards and records, orphaned first.
 */
data class CardView(
    val surfaceId: String,
    val title: String,
    val openedBy: PeerIdentity,
    val openerStatus: String?,
    val openerDeparted: DepartReason?,
    val claims: List<ClaimBadge>,
)

/**
 * What the page shows around the renderer: a card per open surface, in the board's order, and the
 * attached strip. Pure: it reads a board and the live presence, and the DOM is [BoardChrome]'s.
 *
 * **The strip.** Every live peer, one per identity: people first, then agents, by name. An agent
 * the host departed shows as [PeerState.Departed], faded, while its last entry is still on this
 * board, newest [RECENT_DEPARTURES] only: a departed agent stays retired, so the list would only
 * grow over a day.
 */
data class BoardView(val cards: List<CardView>, val peers: List<PeerView>) {
    companion object {
        /** How many departed agents the strip keeps showing. */
        const val RECENT_DEPARTURES = 3

        /** The view of [state], with [live] the entries [us.tractat.bee.core.LivePresence] holds now. */
        fun of(state: BoardState, live: List<PresenceEntry>): BoardView {
            val departures = state.departures().associateBy { it.identity }
            val attached = live.groupBy { it.identity }.mapValues { (_, entries) -> entries.maxBy { it.lastActedAt } }
            val peers = attachedPeers(attached, departures.mapValues { it.value.reason }) + recentlyDeparted(state, attached.keys)
            return BoardView(cards(state, attached, departures.mapValues { it.value.reason }), peers)
        }

        private fun attachedPeers(attached: Map<PeerIdentity, PresenceEntry>, departed: Map<PeerIdentity, DepartReason>): List<PeerView> =
            attached.values
                .map { entry ->
                    val reason = departed[entry.identity]
                    val state = when {
                        reason != null -> PeerState.Departed
                        entry.identity.kind == PeerKind.Human -> PeerState.Here
                        entry.waiting -> PeerState.Waiting
                        else -> PeerState.Acting
                    }
                    PeerView(entry.identity, state, entry.status, reason)
                }
                .sortedWith(compareBy<PeerView>({ it.state == PeerState.Departed }, { it.identity.kind != PeerKind.Human }, { it.identity.toString() }))

        /** Departed agents no longer live whose last entry is still on [state], newest first. */
        private fun recentlyDeparted(state: BoardState, live: Set<PeerIdentity>): List<PeerView> {
            val lastStatus = state.latestPresence().associate { it.identity to it.status }
            return state.departures()
                .filter { it.identity !in live && it.identity in lastStatus }
                .sortedByDescending { it.logIndex }
                .take(RECENT_DEPARTURES)
                .map { PeerView(it.identity, PeerState.Departed, lastStatus[it.identity], it.reason) }
        }

        private fun cards(state: BoardState, attached: Map<PeerIdentity, PresenceEntry>, departed: Map<PeerIdentity, DepartReason>): List<CardView> {
            val claims = claimsBySurface(state)
            return state.openSurfaces().map { id ->
                val meta = requireNotNull(state.meta(id)) { "Open surface $id has no meta." }
                val reason = departed[meta.openedBy]
                CardView(
                    surfaceId = id,
                    title = meta.title,
                    openedBy = meta.openedBy,
                    openerStatus = attached[meta.openedBy]?.status?.takeIf { reason == null },
                    openerDeparted = reason,
                    claims = claims[id].orEmpty().sortedWith(compareBy({ !it.orphaned }, { it.target })),
                )
            }
        }

        /**
         * The held and orphaned claims, by the open surface they are on: a card target names its
         * surface (`card:<surfaceId>/<cardId>`), and a record target its record, which names one.
         */
        private fun claimsBySurface(state: BoardState): Map<String, List<ClaimBadge>> {
            val active = state.claims.entries.filterValues { it.status == ClaimView.HELD || it.status == ClaimView.ORPHANED }
            if (active.isEmpty()) return emptyMap()
            val open = state.openSurfaces()
            val recordSurfaces by lazy {
                state.records.entries().associate { (id, record) -> RecordId(id.dot).claimTarget.value to record.surfaceId }
            }
            return active.entries
                .mapNotNull { (target, view) ->
                    val surface = when {
                        target.startsWith(CARD) -> open.firstOrNull { target.startsWith("$CARD$it/") }
                        else -> recordSurfaces[target]
                    }
                    surface?.let { it to ClaimBadge(target, requireNotNull(view.holder) { "Claim $target has no holder." }, view.status == ClaimView.ORPHANED) }
                }
                .groupBy({ it.first }, { it.second })
        }

        private const val CARD = "card:"
    }
}
