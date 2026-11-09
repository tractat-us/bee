package us.tractat.bee.web

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.MintedInvite
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Where the Invite panel's link comes from. */
internal sealed interface InviteSource {
    /** A `--lan` host's join link: the same every time the panel opens. */
    class Fixed(val links: InviteLinks) : InviteSource

    /**
     * A hosted board's (bee#215): [mint] asks the host for a new invite and calls back with it, or
     * with why not. The panel mints one the first time it opens, and keeps it while it has more
     * than [Invite.REMINT_BEFORE] left.
     */
    class Minted(val mint: (done: (Result<MintedInvite>) -> Unit) -> Unit) : InviteSource
}

/**
 * "Invite": on a page that may invite (never a room screen), a control in the page's bar that
 * opens a panel with a join link as a QR code to scan from a phone or a laptop, the link as text
 * with Copy, and the host's other links.
 *
 * - **Only from the host, which says who may invite.** The links come from `GET`
 *   [InviteLinks.PATH], which a `--lan` host answers on its own machine alone, and a
 *   `--public-url` host to any page it admitted, since any admitted person can admit anyone
 *   (bee#145). Without them there is no control.
 * - **A hosted board's link admits whoever opens it, for a day** (bee#215). The panel mints it
 *   when it first opens ([InviteSource.Minted]): the public URL with a secret in its fragment.
 * - **Legible across a room.** The code is an SVG, [QR_PIXELS] a side or the panel's width,
 *   dark on white whatever the page's theme, with the standard's four-module quiet zone.
 * - **Like Bring your Claude.** The panel sits in the page's flow under the bar and pushes the
 *   board down; its ×, the control again, or Escape closes it. Copy works as there.
 *
 * DOM hooks: `#invite-toggle` (`aria-expanded`), `#invite` (`hidden`), its `#invite-qr` (an
 * `<svg>` whose `data-text` is the link it encodes), `#invite-link`, `#invite-copy`,
 * `#invite-copied`, `#invite-close` and, when the host has more links, `#invite-others`.
 */
internal class Invite(shell: PageShell, private val source: InviteSource) {
    private val toggle: HTMLElement = (element("button", "invite-toggle") as HTMLElement).also {
        it.textContent = "Invite"
        it.setAttribute("type", "button")
        it.setAttribute("aria-controls", PANEL_ID)
        it.setAttribute("aria-expanded", "false")
        shell.bar.insertBefore(it, shell.status)
    }
    private val panel: HTMLElement = (element("section", PANEL_ID) as HTMLElement).also {
        it.setAttribute("aria-label", "Invite")
        it.hidden = true
        requireNotNull(shell.problem.parentElement) { "#problem isn't on the page." }.insertBefore(it, shell.problem.nextSibling)
    }
    private val code: Element = element("div", "invite-code")
    private val line: Element = element("code", "invite-link")
    private val copied: Element = element("p", "invite-copied").also { it.setAttribute("aria-live", "polite") }
    private val others: Element = element("ul", "invite-others").also {
        it.setAttribute("aria-label", "The same link at the host's other addresses")
    }

    /** The link the panel shows, once it has one. */
    private var shown: String? = null

    /** A hosted board's invite, once minted. */
    private var minted: MintedInvite? = null

    init {
        panel.appendChild(heading())
        panel.appendChild(code)
        panel.appendChild(element("p", "invite-scan").also { it.textContent = scanText(source) })
        panel.appendChild(
            element("div", "invite-line").also { row ->
                row.appendChild(line)
                row.appendChild(
                    element("button", "invite-copy").also {
                        it.textContent = "Copy"
                        it.setAttribute("type", "button")
                        it.addEventListener("click", { copy() })
                    },
                )
            },
        )
        panel.appendChild(copied)
        if (source is InviteSource.Fixed) show(source.links)
        toggle.addEventListener("click", { open(panel.hidden) })
        document.addEventListener("keydown", { event ->
            if ((event as KeyboardEvent).key == "Escape" && !panel.hidden) open(false)
        })
    }

    private fun heading(): Element = element("header", "invite-head").also { head ->
        head.appendChild(element("strong", "").also { it.textContent = "Invite" })
        head.appendChild(
            element("button", "invite-close").also {
                it.textContent = "×"
                it.setAttribute("type", "button")
                it.setAttribute("aria-label", "Close")
                it.addEventListener("click", { open(false) })
            },
        )
    }

    private fun open(open: Boolean) {
        panel.hidden = !open
        toggle.setAttribute("aria-expanded", open.toString())
        if (!open) copied.textContent = ""
        if (open && source is InviteSource.Minted && minted.let { it == null || Clock.System.now() >= it.expiresAt - REMINT_BEFORE }) mint(source)
    }

    private fun mint(source: InviteSource.Minted) {
        line.textContent = "Making a link…"
        source.mint { result ->
            result.fold(
                onSuccess = { invite ->
                    minted = invite
                    show(InviteLinks(numeric = emptyList(), named = invite.link, joins = InviteLinks.Joins.Anywhere))
                },
                onFailure = { line.textContent = it.message ?: "Couldn't make a link. Close this and try again." },
            )
        }
    }

    /** Shows [links]: the primary one as a code and as text, and the rest in a list. */
    private fun show(links: InviteLinks) {
        shown = links.primary
        while (code.firstChild != null) code.removeChild(code.firstChild!!)
        code.appendChild(qrSvg(links.primary))
        line.textContent = links.primary
        while (others.firstChild != null) others.removeChild(others.firstChild!!)
        val rest = (links.numeric + links.named).distinct() - links.primary
        rest.forEach { other -> others.appendChild(element("li", "").also { it.textContent = other }) }
        if (rest.isEmpty()) others.remove() else panel.appendChild(others)
    }

    private fun copy() {
        val link = shown ?: return
        writeClipboard(link) { written ->
            copied.textContent = when {
                written || selectAndCopy(line) -> "Copied."
                else -> "Selected: press ${copyKeys()} to copy."
            }
        }
    }

    companion object {
        private const val PANEL_ID = "invite"
        private const val SVG_NS = "http://www.w3.org/2000/svg"

        /** The code's side on screen, in CSS pixels, when the panel is that wide. */
        private const val QR_PIXELS = 320

        /** The standard's light margin around the symbol, in modules. */
        private const val QUIET_ZONE = 4

        /** A minted invite with no more than this left is replaced when the panel opens, so a link handed on has hours to run. */
        val REMINT_BEFORE = 1.hours

        /** Where a device that scans the code can join from, and for a hosted board's minted link, for how long (bee#145, bee#215). */
        private fun scanText(source: InviteSource): String = when (source) {
            is InviteSource.Fixed -> when (source.links.joins) {
                InviteLinks.Joins.OnThisNetwork -> "Scan to join on a phone or laptop on this network."
                InviteLinks.Joins.Anywhere -> "Scan to join on a phone or laptop."
            }
            is InviteSource.Minted -> "Scan to join on a phone or laptop. Anyone with this link can join, for a day."
        }

        private fun element(tag: String, id: String): Element = document.createElement(tag).also { if (id.isNotEmpty()) it.id = id }

        private fun svgElement(tag: String, vararg attributes: Pair<String, String>): Element =
            document.createElementNS(SVG_NS, tag).also { svg -> attributes.forEach { (name, value) -> svg.setAttribute(name, value) } }

        /** [text] as a QR code: one white square, one path of dark modules, crisp at any scale. */
        private fun qrSvg(text: String): Element {
            val code = QrCode.of(text)
            val side = code.size + 2 * QUIET_ZONE
            val modules = buildString {
                for (row in 0 until code.size) {
                    for (column in 0 until code.size) {
                        if (code.dark(row, column)) append("M${column + QUIET_ZONE},${row + QUIET_ZONE}h1v1h-1z")
                    }
                }
            }
            return document.createElementNS(SVG_NS, "svg").also { svg ->
                svg.id = "invite-qr"
                svg.setAttribute("viewBox", "0 0 $side $side")
                svg.setAttribute("width", "$QR_PIXELS")
                svg.setAttribute("height", "$QR_PIXELS")
                svg.setAttribute("shape-rendering", "crispEdges")
                svg.setAttribute("role", "img")
                svg.setAttribute("aria-label", "QR code of the join link")
                svg.setAttribute("data-text", text)
                svg.appendChild(svgElement("rect", "width" to "$side", "height" to "$side", "fill" to "#fff"))
                svg.appendChild(svgElement("path", "d" to modules, "fill" to "#000"))
            }
        }
    }
}
