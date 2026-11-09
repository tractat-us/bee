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
         * The bee mark, side-on and flying right, in the text colour (`#brand` sets the page's
         * accent): the shapes of the favicon (`favicon.svg`), with its cuts letting the bar show
         * through. Each wing is two quilt patches stitched together (kuilt), and the cuts at the
         * waist leave a K (Kotlin) in the negative space.
         */
        private val BEE_MARK: String =
            """
            <svg viewBox="0 0 32 32" width="26" height="26" aria-hidden="true" focusable="false">
              <defs><mask id="bee-mark-cuts"><rect width="32" height="32" fill="#fff"/>
                <path d="M8.8 0h1.6v32h-1.6zM12.2 0h1.6v32h-1.6z" fill="#000"/>
                <path d="M16.6 12V26M17.2 19L22.4 13.8M17.2 19L22.4 24.2" fill="none" stroke="#000" stroke-width="1.3"/>
              </mask></defs>
              <g transform="translate(0 1.5)" fill="currentColor" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
                <g transform="translate(15.3 12.8) rotate(35)">
                  <ellipse rx="4.5" ry="2.4" fill-opacity="0.25" stroke-width="0.8"/>
                  <path d="M0-2.4A4.5 2.4 0 0 0 0 2.4Z" fill-opacity="0.3" stroke="none"/>
                  <path d="M0-2V2" fill="none" stroke-width="0.8" stroke-dasharray="1.1 0.9"/>
                </g>
                <g transform="translate(16.6 10) rotate(60)">
                  <ellipse rx="6" ry="3.2" fill-opacity="0.25" stroke-width="1.4"/>
                  <path d="M0-3.2A6 3.2 0 0 0 0 3.2Z" fill-opacity="0.3" stroke="none"/>
                  <path d="M0-2.8V2.8" fill="none" stroke-width="0.8" stroke-dasharray="1.1 0.9"/>
                </g>
                <path d="M27.6 16.4C28 14.2 29.2 12.8 30.6 12.2" fill="none" stroke-width="1.4"/>
                <g mask="url(#bee-mark-cuts)" stroke="none">
                  <path d="M8 14C11 13.2 14.6 13.4 16.2 15C17.2 16.6 17.2 21.4 16.2 23C14.6 24.6 11 24.8 8 24L3 19Z"/>
                  <circle cx="19.6" cy="19" r="3.8"/>
                </g>
                <path d="M27 16L30 19L27 22A3 3 0 0 1 27 16Z" stroke="none"/>
              </g>
            </svg>
            """.trimIndent()

        private fun element(tag: String, id: String): Element = document.createElement(tag).also { it.id = id }
    }
}
