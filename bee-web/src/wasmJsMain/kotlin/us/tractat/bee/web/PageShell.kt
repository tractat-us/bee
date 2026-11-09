package us.tractat.bee.web

import kotlinx.browser.document
import org.w3c.dom.Element

/**
 * The page around the board, made once the page may show the board (after pairing, if it pairs):
 *
 * - a bar above it ([bar], `#bar`), with the attached strip ([peers], `#peers`) and whether the
 *   page is online ([status], `#status`);
 * - [problem] (`#problem`), under the bar, shown only when it says something;
 * - a collapsed "Details" (`#details`) after the board, holding what the page knows for debugging:
 *   whether this tab keeps the board ([store], `#store`) and every action record ([records],
 *   `#records`).
 *
 * The board itself is index.html's `#board`, where [BoardChrome] puts a card per surface.
 */
class PageShell private constructor(
    val bar: Element,
    val peers: Element,
    val status: Element,
    val problem: Element,
    val store: Element,
    val records: Element,
) {
    companion object {
        /** Builds the shell around [board], which must already be on the page. */
        fun around(board: Element): PageShell {
            val page = requireNotNull(board.parentElement) { "#board isn't on the page." }
            val bar = page.insertBefore(element("header", "bar"), board) as Element
            bar.appendChild(
                element("span", "brand").also {
                    it.setAttribute("role", "img")
                    it.setAttribute("aria-label", "bee")
                    it.innerHTML = BEE_MARK
                },
            )
            val peers = bar.appendChild(element("ul", "peers").also { it.setAttribute("aria-label", "Attached") }) as Element
            val status = bar.appendChild(element("p", "status")) as Element
            val problem = page.insertBefore(element("p", "problem").also { it.setAttribute("role", "alert") }, board) as Element
            val details = page.appendChild(element("details", "details")) as Element
            details.appendChild(document.createElement("summary").also { it.textContent = "Details" })
            val store = details.appendChild(element("p", "store")) as Element
            val records = details.appendChild(element("ol", "records")) as Element
            return PageShell(bar, peers, status, problem, store, records)
        }

        /**
         * The bee mark, in the text colour (`#brand` sets the page's accent): the shapes of the
         * favicon (`favicon.svg`), whose stripes are cut out so the bar shows through. Each wing is
         * two quilt patches stitched together (kuilt).
         */
        private val BEE_MARK: String =
            """<svg viewBox="0 0 32 32" width="26" height="26" aria-hidden="true" focusable="false">""" +
                """<defs><mask id="bee-mark-stripes"><rect width="32" height="32" fill="#fff"/>""" +
                """<path d="M8 18.2Q16 19.4 24 18.2V20.6Q16 21.8 8 20.6ZM8 23.2Q16 24.4 24 23.2V25.6Q16 26.8 8 25.6Z" fill="#000"/></mask></defs>""" +
                """<g fill="currentColor" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">""" +
                """<path d="M14.4 7.6C13.6 5 12 3.6 9.6 3.2M17.6 7.6C18.4 5 20 3.6 22.4 3.2" fill="none" stroke-width="1.8"/>""" +
                wing(placed = "translate(8.6 12.6) rotate(28)", outerSweep = 0) +
                wing(placed = "translate(23.4 12.6) rotate(-28)", outerSweep = 1) +
                """<circle cx="16" cy="10" r="3" stroke="none"/>""" +
                """<ellipse cx="16" cy="21.6" rx="5.6" ry="7.6" mask="url(#bee-mark-stripes)" stroke="none"/></g></svg>"""

        /** One wing, [placed] beside the body: its outer patch (drawn by the arc's [outerSweep]) a shade darker, and a stitched seam between the two. */
        private fun wing(placed: String, outerSweep: Int): String =
            """<g transform="$placed"><ellipse rx="6" ry="3.6" fill-opacity="0.25" stroke-width="1.8"/>""" +
                """<path d="M0-3.6A6 3.6 0 0 $outerSweep 0 3.6Z" fill-opacity="0.3" stroke="none"/>""" +
                """<path d="M0-3.2V3.2" fill="none" stroke-width="0.8" stroke-dasharray="1.1 0.9"/></g>"""

        private fun element(tag: String, id: String): Element = document.createElement(tag).also { it.id = id }
    }
}
