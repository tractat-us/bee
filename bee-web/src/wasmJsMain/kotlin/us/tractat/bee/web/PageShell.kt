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
         * favicon (`favicon.svg`), whose stripes are cut out so the bar shows through.
         */
        private const val BEE_MARK: String =
            """<svg viewBox="0 0 32 32" width="26" height="26" aria-hidden="true" focusable="false">""" +
                """<defs><mask id="bee-mark-stripes"><rect width="32" height="32" fill="#fff"/>""" +
                """<rect y="18.4" width="32" height="2.6" fill="#000"/><rect y="23.6" width="32" height="2.6" fill="#000"/></mask></defs>""" +
                """<g fill="currentColor" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">""" +
                """<path d="M14.4 7.6C13.6 5 12 3.6 9.6 3.2M17.6 7.6C18.4 5 20 3.6 22.4 3.2" fill="none" stroke-width="1.8"/>""" +
                """<ellipse cx="8.6" cy="12.6" rx="6" ry="3.6" transform="rotate(28 8.6 12.6)" fill-opacity="0.3" stroke-width="1.8"/>""" +
                """<ellipse cx="23.4" cy="12.6" rx="6" ry="3.6" transform="rotate(-28 23.4 12.6)" fill-opacity="0.3" stroke-width="1.8"/>""" +
                """<circle cx="16" cy="10" r="3" stroke="none"/>""" +
                """<ellipse cx="16" cy="21.6" rx="5.6" ry="7.6" mask="url(#bee-mark-stripes)" stroke="none"/></g></svg>"""

        private fun element(tag: String, id: String): Element = document.createElement(tag).also { it.id = id }
    }
}
