package us.tractat.bee.web

import us.tractat.bee.core.BoardState
import us.tractat.bee.core.ClaimView
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.core.PresenceEntry
import us.tractat.bee.core.RecordId

/** How a peer shows in the attached strip (spec §6.4). [name] is its `data-state`. */
sealed interface PeerState {
    val name: String

    /** An agent waiting on the board for a person. */
    data object Waiting : PeerState {
        override val name = "waiting"
    }

    /** An agent at work. */
    data object Acting : PeerState {
        override val name = "acting"
    }

    /** A person, or a room screen. */
    data object Here : PeerState {
        override val name = "here"
    }

    /** A retired agent, for [reason]: crashed or quiet, as the host judged, or cleared or left, as it said. */
    data class Departed(val reason: DepartReason) : PeerState {
        override val name = "departed"
    }
}

/** One peer in the attached strip. [status] is its "working on…" line. */
data class PeerView(val identity: PeerIdentity, val state: PeerState, val status: String?)

/** A claim on something a card shows: held by [holder], or orphaned when its holder departed. */
data class ClaimBadge(val target: String, val holder: PeerIdentity, val orphaned: Boolean)

/**
 * Where a card's opener is: attached, retired by the host, or neither. An attached agent is
 * [Listening] or [Working], since what it is doing says whether an answer on its card is heard
 * now; a person or the host is just [Attached].
 */
sealed interface OpenerState {
    /** A person or the host, attached now, with its "working on…" line if it has one. */
    data class Attached(val status: String?) : OpenerState

    /** An agent attached and in `bee_wait_action`: an answer on its card reaches it now. */
    data class Listening(val status: String?) : OpenerState

    /** An agent attached and at work, not waiting: it reads answers at its next wait. */
    data class Working(val status: String?) : OpenerState

    /** Neither attached nor departed: a person who closed the page, or a peer between posts. */
    data object Away : OpenerState

    /** Retired by the host for [reason]. It shows so even while its entry is still live. */
    data class Departed(val reason: DepartReason) : OpenerState
}

/**
 * One open surface as a card: its [title], who opened it, and where that opener is ([opener]).
 * [claims] are the held and orphaned claims on the surface's cards and records, orphaned first.
 */
data class CardView(
    val surfaceId: String,
    val title: String,
    val openedBy: PeerIdentity,
    val opener: OpenerState,
    val claims: List<ClaimBadge>,
) {
    /**
     * Whether no session of the opener's will take an answer on this card: it left, or the host
     * judged it crashed or quiet ("declared dead, exactly as if it had crashed", spec §6.4). The
     * card's actions stay live, because a record is addressed to its surface, never to a session,
     * and any session may wait on any surface (§5.2); they only show muted.
     *
     * A `/clear` is not one: the bridge goes on handling the surfaces it opened under the identity
     * it held before (§5.2), so an answer there is still heard.
     */
    val unheard: Boolean get() = opener is OpenerState.Departed && opener.reason != DepartReason.Cleared
}

/**
 * What the page shows around the renderer: a card per open surface, and the attached strip. Pure:
 * it reads a board and the live presence, and the DOM is [BoardChrome]'s.
 *
 * **The cards.** Newest first: the board's open order ([BoardState.openSurfaces], by each
 * surface's `openedAt`) reversed, so a surface an agent draws mid-meeting shows on top, where the
 * room sees it appear, not below a tall card off-screen. An update leaves `openedAt` alone, so a
 * card someone is reading never moves; a reopened surface keeps its original place.
 *
 * **The strip.** Every attached peer: people first, then agents, by name. An agent the host
 * departed shows as [PeerState.Departed], faded, while its last entry is still on this board,
 * newest [RECENT_DEPARTURES] only: a departed agent stays retired, so the list would only grow
 * over a day.
 */
data class BoardView(val cards: List<CardView>, val peers: List<PeerView>) {
    companion object {
        /** How many departed agents the strip keeps showing. */
        const val RECENT_DEPARTURES = 3

        /**
         * The view of [state], with [attached] the peers [us.tractat.bee.core.LivePresence.peers]
         * holds now, one entry per identity.
         */
        fun of(state: BoardState, attached: Map<PeerIdentity, PresenceEntry>): BoardView {
            val departed = state.departures().associate { it.identity to it.reason }
            val peers = attachedPeers(attached.values, departed) + recentlyDeparted(state, attached.keys)
            return BoardView(cards(state, attached, departed), peers)
        }

        private fun attachedPeers(attached: Collection<PresenceEntry>, departed: Map<PeerIdentity, DepartReason>): List<PeerView> =
            attached
                .map { entry ->
                    val reason = departed[entry.identity]
                    val state = when {
                        reason != null -> PeerState.Departed(reason)
                        entry.identity.kind == PeerKind.Human -> PeerState.Here
                        entry.waiting -> PeerState.Waiting
                        else -> PeerState.Acting
                    }
                    PeerView(entry.identity, state, entry.status)
                }
                .sortedWith(compareBy<PeerView>({ it.state is PeerState.Departed }, { it.identity.kind != PeerKind.Human }, { it.identity.toString() }))

        /** Departed agents no longer live whose last entry is still on [state], newest first. */
        private fun recentlyDeparted(state: BoardState, live: Set<PeerIdentity>): List<PeerView> {
            val lastStatus = state.latestPresence().associate { it.identity to it.status }
            return state.departures()
                .filter { it.identity !in live && it.identity in lastStatus }
                .sortedByDescending { it.logIndex }
                .take(RECENT_DEPARTURES)
                .map { PeerView(it.identity, PeerState.Departed(it.reason), lastStatus[it.identity]) }
        }

        private fun cards(state: BoardState, attached: Map<PeerIdentity, PresenceEntry>, departed: Map<PeerIdentity, DepartReason>): List<CardView> {
            val claims = claimsBySurface(state)
            return state.openSurfaces().asReversed().map { id ->
                val meta = requireNotNull(state.meta(id)) { "Open surface $id has no meta." }
                CardView(
                    surfaceId = id,
                    title = meta.title,
                    openedBy = meta.openedBy,
                    opener = opener(meta.openedBy, departed[meta.openedBy], attached[meta.openedBy]),
                    claims = claims[id].orEmpty().sortedWith(compareBy({ !it.orphaned }, { it.target })),
                )
            }
        }

        private fun opener(openedBy: PeerIdentity, departed: DepartReason?, attached: PresenceEntry?): OpenerState = when {
            departed != null -> OpenerState.Departed(departed)
            attached == null -> OpenerState.Away
            openedBy.kind != PeerKind.Agent -> OpenerState.Attached(attached.status)
            attached.waiting -> OpenerState.Listening(attached.status)
            else -> OpenerState.Working(attached.status)
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
                    surface?.let { it to ClaimBadge(target, holder(target, view), view.status == ClaimView.ORPHANED) }
                }
                .groupBy({ it.first }, { it.second })
        }

        /** A held or orphaned claim's holder. The host mirrors it from a [PeerIdentity], so it parses. */
        private fun holder(target: String, view: ClaimView): PeerIdentity =
            PeerIdentity.parse(requireNotNull(view.holder) { "Claim $target is ${view.status} with no holder." })

        private const val CARD = "card:"
    }
}
