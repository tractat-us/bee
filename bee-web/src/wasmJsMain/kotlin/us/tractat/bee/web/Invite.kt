package us.tractat.bee.web

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import us.tractat.bee.core.InviteLinks

/**
 * "Invite": on a page that may invite (never a room screen), a control in the page's bar that
 * opens a panel with the host's join link as a QR code to scan from a phone or a laptop in the
 * room, the link as text with Copy, and the host's other links.
 *
 * - **Only from the host, which says who may invite.** The links come from `GET`
 *   [InviteLinks.PATH], which a `--lan` host answers on its own machine alone, and a
 *   `--public-url` host to any page it admitted, with its bare public URL, since any admitted
 *   person can admit anyone (bee#145). Without them there is no control.
 * - **Legible across a room.** The code is an SVG, [QR_PIXELS] a side or the panel's width,
 *   dark on white whatever the page's theme, with the standard's four-module quiet zone.
 * - **Like Bring your Claude.** The panel sits in the page's flow under the bar and pushes the
 *   board down; its ×, the control again, or Escape closes it. Copy works as there.
 *
 * DOM hooks: `#invite-toggle` (`aria-expanded`), `#invite` (`hidden`), its `#invite-qr` (an
 * `<svg>` whose `data-text` is the link it encodes), `#invite-link`, `#invite-copy`,
 * `#invite-copied`, `#invite-close` and, when the host has more links, `#invite-others`.
 */
internal class Invite(shell: PageShell, private val links: InviteLinks) {
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
    private val line: Element = element("code", "invite-link").also { it.textContent = links.primary }
    private val copied: Element = element("p", "invite-copied").also { it.setAttribute("aria-live", "polite") }

    init {
        panel.appendChild(heading())
        panel.appendChild(element("div", "invite-code").also { it.appendChild(qrSvg(links.primary)) })
        panel.appendChild(element("p", "invite-scan").also { it.textContent = scanText(links.joins) })
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
        val others = (links.numeric + links.named).distinct() - links.primary
        if (others.isNotEmpty()) {
            panel.appendChild(
                element("ul", "invite-others").also { list ->
                    list.setAttribute("aria-label", "The same link at the host's other addresses")
                    others.forEach { other -> list.appendChild(element("li", "").also { it.textContent = other }) }
                },
            )
        }
        toggle.addEventListener("click", { show(panel.hidden) })
        document.addEventListener("keydown", { event ->
            if ((event as KeyboardEvent).key == "Escape" && !panel.hidden) show(false)
        })
    }

    private fun heading(): Element = element("header", "invite-head").also { head ->
        head.appendChild(element("strong", "").also { it.textContent = "Invite" })
        head.appendChild(
            element("button", "invite-close").also {
                it.textContent = "×"
                it.setAttribute("type", "button")
                it.setAttribute("aria-label", "Close")
                it.addEventListener("click", { show(false) })
            },
        )
    }

    private fun show(open: Boolean) {
        panel.hidden = !open
        toggle.setAttribute("aria-expanded", open.toString())
        if (!open) copied.textContent = ""
    }

    private fun copy() {
        writeClipboard(links.primary) { written ->
            copied.textContent = when {
                written || selectAndCopy(line) -> "Copied."
                else -> "Selected: press ${copyKeys()} to copy."
            }
        }
    }

    private companion object {
        const val PANEL_ID = "invite"
        const val SVG_NS = "http://www.w3.org/2000/svg"

        /** The code's side on screen, in CSS pixels, when the panel is that wide. */
        const val QR_PIXELS = 320

        /** The standard's light margin around the symbol, in modules. */
        const val QUIET_ZONE = 4

        /** Where a device that scans the code can join from: a hosted board's link works anywhere (bee#145). */
        fun scanText(joins: InviteLinks.Joins): String = when (joins) {
            InviteLinks.Joins.OnThisNetwork -> "Scan to join on a phone or laptop on this network."
            InviteLinks.Joins.Anywhere -> "Scan to join on a phone or laptop."
        }

        fun element(tag: String, id: String): Element = document.createElement(tag).also { if (id.isNotEmpty()) it.id = id }

        fun svgElement(tag: String, vararg attributes: Pair<String, String>): Element =
            document.createElementNS(SVG_NS, tag).also { svg -> attributes.forEach { (name, value) -> svg.setAttribute(name, value) } }

        /** [text] as a QR code: one white square, one path of dark modules, crisp at any scale. */
        fun qrSvg(text: String): Element {
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
