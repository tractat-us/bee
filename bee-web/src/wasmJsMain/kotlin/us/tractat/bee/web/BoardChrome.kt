package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import us.tractat.bee.core.BoardState
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The board's chrome around the renderer ([BoardView]): a card per open surface, with its title,
 * its opener and that opener's "working on…" line, and a badge per claim on it; and the attached
 * strip, [strip].
 *
 * - **Cards hold the renderer's surfaces.** The renderer mounts each `<a2ui-surface>` where [slot]
 *   says, inside its card under [root], so a surface and its header are one element on the page.
 *   A card is made by whichever comes first, the renderer or the board's view.
 * - **A placeholder joins its card.** [BoardDriver] puts an invalid surface's placeholder in
 *   [root]; the chrome moves it into that surface's card as soon as it appears.
 * - **Live presence ages without a board change**, so the view is redrawn every [TICK] as well.
 *
 * DOM hooks: `article.bee-card[data-surface-id]`, its `.bee-card-title` and `.bee-card-opener`
 * (`data-peer`, the opener's identity), `.bee-card-working`, and `.bee-badge`
 * (`data-claim-status`); in the strip, `li.bee-peer[data-peer][data-state]` with `.bee-peer-status`.
 */
class BoardChrome(private val root: Element, private val strip: Element) {
    private val cards = mutableMapOf<String, Card>()
    private var peers: List<PeerView>? = null

    init {
        observeChildren(root) { adoptPlaceholders() }
    }

    /** Where surface [surfaceId]'s renderer output goes: its card's body. */
    fun slot(surfaceId: String): Element = card(surfaceId).body

    /** Shows [board] and its live presence from [roster] as they change, until [scope] ends. */
    fun start(scope: CoroutineScope, board: StateFlow<BoardState>, roster: LivePresence): Job = scope.launch {
        combine(board, ticks(TICK)) { state, _ -> state }.collect { state -> show(BoardView.of(state, roster.live())) }
    }

    fun show(view: BoardView) {
        val open = view.cards.map { it.surfaceId }.toSet()
        (cards.keys - open).forEach { id -> cards.remove(id)?.element?.remove() }
        view.cards.forEach { card(it.surfaceId).show(it) }
        keepOrder(view.cards.map { it.surfaceId })
        adoptPlaceholders()
        if (view.peers != peers) showPeers(view.peers)
    }

    private fun card(id: String): Card = cards.getOrPut(id) { Card(id).also { root.appendChild(it.element) } }

    /** Moves a card only when it is out of the board's order: a move re-attaches its surface. */
    private fun keepOrder(order: List<String>) {
        val shown = cardElements().map { it.getAttribute(SURFACE_ID) }
        if (shown.filter { it in order } == order) return
        order.forEach { id -> cards[id]?.let { root.appendChild(it.element) } }
    }

    private fun cardElements(): List<Element> =
        (0 until root.childElementCount).mapNotNull { root.children.item(it) }.filter { it.classList.contains(CARD_CLASS) }

    private fun adoptPlaceholders() {
        (0 until root.childElementCount).mapNotNull { root.children.item(it) }
            .filter { it.classList.contains(PLACEHOLDER_CLASS) }
            .forEach { placeholder -> placeholder.getAttribute(SURFACE_ID)?.let { slot(it).appendChild(placeholder) } }
    }

    private fun showPeers(shown: List<PeerView>) {
        peers = shown
        while (strip.firstChild != null) strip.firstChild?.let { strip.removeChild(it) }
        shown.forEach { peer ->
            val item = strip.appendChild(element("li", "bee-peer")) as Element
            item.setAttribute(PEER, peer.identity.toString())
            item.setAttribute("data-state", peer.state.name.lowercase())
            item.setAttribute("title", "${peer.identity}: ${label(peer)}")
            item.appendChild(name(peer.identity, "bee-peer-name"))
            item.appendChild(element("span", "bee-peer-state").also { it.textContent = label(peer) })
            peer.status?.let { status -> item.appendChild(element("span", "bee-peer-status").also { it.textContent = status }) }
        }
    }

    /** One card: a header the chrome writes, and a body the renderer writes. */
    private class Card(id: String) {
        val element: Element = element("article", CARD_CLASS).also { it.setAttribute(SURFACE_ID, id) }
        private val head = element.appendChild(element("header", "bee-card-head")) as Element
        val body: Element = element.appendChild(element("div", "bee-card-body")) as Element
        private var shown: CardView? = null

        fun show(view: CardView) {
            if (view == shown) return
            shown = view
            while (head.firstChild != null) head.firstChild?.let { head.removeChild(it) }
            head.appendChild(element("h2", "bee-card-title").also { it.textContent = view.title })
            val meta = head.appendChild(element("p", "bee-card-meta")) as Element
            meta.appendChild(element("span", "bee-card-by").also { it.textContent = "opened by " })
            meta.appendChild(name(view.openedBy, "bee-card-opener"))
            view.openerStatus?.let { status -> meta.appendChild(element("span", "bee-card-working").also { it.textContent = status }) }
            view.openerDeparted?.let { reason -> meta.appendChild(element("span", "bee-card-gone").also { it.textContent = departedText(reason) }) }
            if (view.claims.isEmpty()) return
            val badges = head.appendChild(element("ul", "bee-badges")) as Element
            view.claims.forEach { claim ->
                val badge = badges.appendChild(element("li", "bee-badge")) as Element
                badge.setAttribute("data-claim-status", if (claim.orphaned) "orphaned" else "held")
                badge.setAttribute("data-claim-target", claim.target)
                badge.setAttribute("title", "${claim.target}, ${if (claim.orphaned) "orphaned" else "held"} by ${claim.holder}")
                badge.textContent = if (claim.orphaned) "orphaned: ${shortName(claim.holder)} left" else "claimed by ${shortName(claim.holder)}"
            }
        }
    }

    private companion object {
        /** How often live presence is re-read, for entries that age out with no board change. */
        val TICK: Duration = 2.seconds

        const val CARD_CLASS = "bee-card"
        const val PLACEHOLDER_CLASS = "bee-invalid-surface"
        const val SURFACE_ID = "data-surface-id"
        const val PEER = "data-peer"

        fun ticks(every: Duration): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(every)
            }
        }

        fun label(peer: PeerView): String = when (peer.state) {
            PeerState.Waiting -> "waiting"
            PeerState.Acting -> "acting"
            PeerState.Here -> "here"
            PeerState.Departed -> departedText(requireNotNull(peer.departed) { "${peer.identity} departed for no reason." })
        }

        fun departedText(reason: DepartReason): String = when (reason) {
            DepartReason.Crashed -> "crashed"
            DepartReason.Quiet -> "gone quiet"
            DepartReason.Cleared -> "cleared"
        }

        /** [identity] as a name, an agent's with its instance after it, the whole identity in `data-peer`. */
        fun name(identity: PeerIdentity, className: String): Element = element("span", className).also {
            it.setAttribute(PEER, identity.toString())
            it.textContent = identity.name
            if (identity.kind == PeerKind.Agent) {
                it.appendChild(element("span", "bee-instance").also { tag -> tag.textContent = "#${identity.instance.take(4)}" })
            }
        }

        /** A claim holder as shown in a badge: its name, or the raw text if it isn't an identity. */
        fun shortName(holder: String): String = try {
            PeerIdentity.parse(holder).name
        } catch (_: IllegalArgumentException) {
            holder
        }

        fun element(tag: String, className: String): Element = document.createElement(tag).also { it.className = className }
    }
}

/** Calls [onChange] after each change to [root]'s own children. */
@JsFun("(root, onChange) => new MutationObserver(() => onChange()).observe(root, { childList: true })")
private external fun observeChildren(root: Element, onChange: () -> Unit)
