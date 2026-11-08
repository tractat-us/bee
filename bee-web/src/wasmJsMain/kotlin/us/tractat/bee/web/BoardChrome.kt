package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
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
 * - **Cards hold the renderer's surfaces.** The renderer mounts each `<a2ui-surface>` through [place],
 *   inside its card under [root], so a surface and its header are one element on the page.
 *   A card is made by whichever comes first, the renderer or the board's view.
 * - **Cards never move.** [root] is a flex column, and each card's CSS `order` is its place in
 *   the board's order. Moving a node would re-attach its surface: a focused field loses focus,
 *   and a Sketch iframe reloads and drops its pending proposals.
 * - **A placeholder joins its card.** [BoardDriver] puts an invalid surface's placeholder in
 *   [root]; the chrome moves it into that surface's card as soon as it appears.
 * - **Live presence ages without a board change**, so the view is redrawn every [TICK] as well.
 * - **An empty board says how to start.** With no surface open, [root] shows a quiet hint
 *   (`.bee-empty-hint`): what the board is, and how a Claude session draws on it. It appears only
 *   once the page is online and the board has stayed empty for [HINT_DELAY], because a page goes
 *   online a moment before the host's board reaches it, and the hint mustn't flash over a full
 *   board. It hides as soon as a surface opens.
 *
 * DOM hooks: `article.bee-card[data-surface-id]`, its `.bee-card-title` and `.bee-card-opener`
 * (`data-peer`, the opener's identity), `.bee-card-working`, and `.bee-badge`
 * (`data-claim-status`); in the strip, `li.bee-peer[data-peer][data-state]` with `.bee-peer-status`.
 */
class BoardChrome(private val root: Element, private val strip: Element) {
    private val cards = mutableMapOf<String, Card>()
    private var peers: List<PeerView>? = null
    private val hint: HTMLElement = (root.appendChild(emptyHint()) as HTMLElement).also { it.hidden = true }
    private val empty = MutableStateFlow(false)

    init {
        observeChildren(root) { adoptPlaceholders() }
    }

    /** Puts surface [surfaceId]'s renderer [element] in its card's body. */
    fun place(surfaceId: String, element: Element) {
        slot(surfaceId).appendChild(element)
    }

    private fun slot(surfaceId: String): Element = card(surfaceId).body

    /**
     * Shows [board] and its live presence from [roster] as they change, until [scope] ends, and
     * the empty-board hint while the board is empty and [online].
     */
    fun start(scope: CoroutineScope, board: StateFlow<BoardState>, roster: LivePresence, online: StateFlow<Boolean>): Job = scope.launch {
        launch {
            combine(empty, online) { isEmpty, isOnline -> isEmpty && isOnline }.distinctUntilChanged().collectLatest { quiet ->
                if (quiet) delay(HINT_DELAY)
                hint.hidden = !quiet
            }
        }
        combine(board, ticks(TICK)) { state, _ -> state }.collect { state -> show(BoardView.of(state, roster.peers())) }
    }

    fun show(view: BoardView) {
        // Hidden here, not only by the hint's own collector, so it's never shown beside a card.
        if (view.cards.isNotEmpty()) hint.hidden = true
        empty.value = view.cards.isEmpty()
        val open = view.cards.map { it.surfaceId }.toSet()
        (cards.keys - open).forEach { id -> cards.remove(id)?.element?.remove() }
        view.cards.forEachIndexed { index, it -> card(it.surfaceId).show(it, index) }
        adoptPlaceholders()
        if (view.peers != peers) showPeers(view.peers)
    }

    private fun card(id: String): Card = cards.getOrPut(id) { Card(id).also { root.appendChild(it.element) } }

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
            item.setAttribute("data-state", peer.state.name)
            item.setAttribute("title", "${peer.identity}: ${label(peer)}")
            item.appendChild(name(peer.identity, "bee-peer-name"))
            item.appendChild(element("span", "bee-peer-state").also { it.textContent = label(peer) })
            peer.status?.let { status -> item.appendChild(element("span", "bee-peer-status").also { it.textContent = status }) }
        }
    }

    /** One card: a header the chrome writes, and a body the renderer writes. */
    private class Card(id: String) {
        val element: HTMLElement = (element("article", CARD_CLASS) as HTMLElement).also { it.setAttribute(SURFACE_ID, id) }
        private val head = element.appendChild(element("header", "bee-card-head")) as Element
        val body: Element = element.appendChild(element("div", "bee-card-body")) as Element
        private var shown: CardView? = null

        /** Shows [view] as the board's [index]th card. */
        fun show(view: CardView, index: Int) {
            element.style.order = index.toString()
            if (view == shown) return
            shown = view
            while (head.firstChild != null) head.firstChild?.let { head.removeChild(it) }
            head.appendChild(element("h2", "bee-card-title").also { it.textContent = view.title })
            val meta = head.appendChild(element("p", "bee-card-meta")) as Element
            meta.appendChild(element("span", "bee-card-by").also { it.textContent = "opened by " })
            meta.appendChild(name(view.openedBy, "bee-card-opener"))
            when (val opener = view.opener) {
                is OpenerState.Attached -> opener.status?.let { status -> meta.appendChild(element("span", "bee-card-working").also { it.textContent = status }) }
                is OpenerState.Departed -> meta.appendChild(element("span", "bee-card-gone").also { it.textContent = departedText(opener.reason) })
                OpenerState.Away -> Unit
            }
            if (view.claims.isEmpty()) return
            val badges = head.appendChild(element("ul", "bee-badges")) as Element
            view.claims.forEach { claim ->
                val badge = badges.appendChild(element("li", "bee-badge")) as Element
                badge.setAttribute("data-claim-status", if (claim.orphaned) "orphaned" else "held")
                badge.setAttribute("data-claim-target", claim.target)
                badge.setAttribute("title", "${claim.target}, ${if (claim.orphaned) "orphaned" else "held"} by ${claim.holder}")
                badge.textContent = if (claim.orphaned) "orphaned: ${claim.holder.name} left" else "claimed by ${claim.holder.name}"
            }
        }
    }

    private companion object {
        /** How often live presence is re-read, for entries that age out with no board change. */
        val TICK: Duration = 2.seconds

        /** How long the board must stay empty, online, before the hint shows. */
        val HINT_DELAY: Duration = 1.seconds

        const val CARD_CLASS = "bee-card"
        const val PLACEHOLDER_CLASS = "bee-invalid-surface"
        const val SURFACE_ID = "data-surface-id"
        const val PEER = "data-peer"
        const val HINT_CLASS = "bee-empty-hint"

        /** The empty board's hint: what it is, and the whiteboard prompt that starts a session on it. */
        fun emptyHint(): Element = element("p", HINT_CLASS).also { hint ->
            hint.appendChild(element("strong", "bee-empty-title").also { it.textContent = "Nothing on the board yet." })
            hint.appendChild(element("span", "bee-empty-how").also { how ->
                how.appendChild(document.createTextNode("The people here and their Claude sessions all share this board. Ask your session to draw on it: try "))
                how.appendChild(element("code", "bee-empty-prompt").also { it.textContent = "/mcp__bee__whiteboard" })
                how.appendChild(document.createTextNode("."))
            })
        }

        fun ticks(every: Duration): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(every)
            }
        }

        fun label(peer: PeerView): String = when (val state = peer.state) {
            PeerState.Waiting -> "waiting"
            PeerState.Acting -> "acting"
            PeerState.Here -> "here"
            is PeerState.Departed -> departedText(state.reason)
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

        fun element(tag: String, className: String): Element = document.createElement(tag).also { it.className = className }
    }
}

/** Calls [onChange] after each change to [root]'s own children. */
@JsFun("(root, onChange) => new MutationObserver(() => onChange()).observe(root, { childList: true })")
private external fun observeChildren(root: Element, onChange: () -> Unit)
