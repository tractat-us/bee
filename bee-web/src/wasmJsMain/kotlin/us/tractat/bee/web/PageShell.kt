package us.tractat.bee.web

import kotlinx.browser.document
import org.w3c.dom.Element

/**
 * The page around the board, made once the page may show the board (after pairing, if it pairs):
 *
 * - a bar above it, with the attached strip ([peers], `#peers`) and whether the page is online
 *   ([status], `#status`);
 * - [problem] (`#problem`), under the bar, shown only when it says something;
 * - a collapsed "Details" (`#details`) after the board, holding what the page knows for debugging:
 *   whether this tab keeps the board ([store], `#store`) and every action record ([records],
 *   `#records`).
 *
 * The board itself is index.html's `#board`, where [BoardChrome] puts a card per surface.
 */
class PageShell private constructor(
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
            bar.appendChild(element("span", "brand").also { it.textContent = "bee" })
            val peers = bar.appendChild(element("ul", "peers").also { it.setAttribute("aria-label", "Attached") }) as Element
            val status = bar.appendChild(element("p", "status")) as Element
            val problem = page.insertBefore(element("p", "problem").also { it.setAttribute("role", "alert") }, board) as Element
            val details = page.appendChild(element("details", "details")) as Element
            details.appendChild(document.createElement("summary").also { it.textContent = "Details" })
            val store = details.appendChild(element("p", "store")) as Element
            val records = details.appendChild(element("ol", "records")) as Element
            return PageShell(peers, status, problem, store, records)
        }

        private fun element(tag: String, id: String): Element = document.createElement(tag).also { it.id = id }
    }
}
