package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** Who views this page: a person `?name=` names, or one the page can't name. */
internal sealed interface Viewer {
    data class Named(val name: String) : Viewer

    data object Unnamed : Viewer
}

/**
 * The one line that points a viewer's own Claude session at this board: bee-host's MCP endpoint
 * at [PATH], on the [origin] the page came from, with this tab's key (none from loopback, which
 * needs none) and the viewer's name.
 *
 * `--scope user` because the session it brings may start in any folder: `claude mcp add`'s own
 * default scope is the folder it runs in. The line first removes any earlier `bee` entry, quietly,
 * since `claude mcp add` refuses a name it already has: one line still works with a new key or
 * for another board. The whole URL is one single-quoted shell word, every `'` in it escaped: a
 * browser keeps `'`, `;` and `$(` in a host name, so neither the origin nor a name can end the quote.
 */
internal class McpCommand private constructor(val line: String, val viewer: Viewer) {
    companion object {
        /** bee-host's MCP Streamable HTTP route. */
        const val PATH = "/mcp"

        /** The name the line carries for a viewer the page can't name. */
        const val PLACEHOLDER = "you"

        /** Drops an earlier `bee` entry, saying nothing whether or not there was one. */
        private const val REMOVE_EARLIER = "claude mcp remove --scope user bee >/dev/null 2>&1;"

        fun of(origin: String, access: Access.Admitted, viewer: Viewer): McpCommand {
            val name = when (viewer) {
                is Viewer.Named -> viewer.name
                Viewer.Unnamed -> PLACEHOLDER
            }
            val key = when (access) {
                is Access.Keyed -> listOf("k" to access.key)
                Access.Loopback -> emptyList()
            }
            val query = (key + ("name" to name)).joinToString("&") { (param, value) -> "$param=${encodeURIComponent(value)}" }
            return McpCommand("$REMOVE_EARLIER claude mcp add --scope user --transport http bee ${singleQuoted("$origin$PATH?$query")}", viewer)
        }

        /** [text] as one inert POSIX shell word: single quotes, each `'` in it closed, escaped and reopened. */
        private fun singleQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"
    }
}

/**
 * "Bring your Claude": a control in the page's bar that opens a panel with [command], to copy and
 * run in a terminal, then how to start the session it brings.
 *
 * - **The panel never covers the board.** It sits in the page's flow under the bar, as `#problem`
 *   does, and pushes the board down while open. Its close button, the control again, or Escape
 *   closes it.
 * - **The key stays in this tab's DOM.** The command carries this tab's key, so it is written to
 *   the panel's text and nowhere else: not the address bar, not a log, not the board.
 * - **Copy works on a plain-HTTP LAN page.** The async clipboard needs a secure context, which a
 *   LAN page is not. Without it the line is selected and copied the old way, and failing that the
 *   panel says to press the copy keys.
 *
 * DOM hooks: `#bring-claude-toggle` (`aria-expanded`), `#bring-claude` (`hidden`), its
 * `#bring-claude-command`, `#bring-claude-copy`, `#bring-claude-copied` and, for an unnamed
 * viewer, `#bring-claude-unnamed`; [opener] makes a `.bring-claude-open` button for elsewhere.
 */
internal class BringYourClaude(shell: PageShell, private val command: McpCommand) {
    private val toggle: HTMLElement = (element("button", "bring-claude-toggle") as HTMLElement).also {
        it.textContent = "Bring your Claude"
        it.setAttribute("type", "button")
        it.setAttribute("aria-controls", PANEL_ID)
        it.setAttribute("aria-expanded", "false")
        shell.bar.insertBefore(it, shell.status)
    }
    private val panel: HTMLElement = (element("section", PANEL_ID) as HTMLElement).also {
        it.setAttribute("aria-label", "Bring your Claude")
        it.hidden = true
        requireNotNull(shell.problem.parentElement) { "#problem isn't on the page." }.insertBefore(it, shell.problem.nextSibling)
    }
    private val line: Element = element("code", "bring-claude-command").also { it.textContent = command.line }
    private val copied: Element = element("p", "bring-claude-copied").also { it.setAttribute("aria-live", "polite") }

    init {
        panel.appendChild(heading())
        panel.appendChild(element("p", "").also { it.textContent = "In a terminal, run this. It replaces any earlier bee entry." })
        panel.appendChild(
            element("div", "bring-claude-line").also { row ->
                row.appendChild(line)
                row.appendChild(
                    element("button", "bring-claude-copy").also {
                        it.textContent = "Copy"
                        it.setAttribute("type", "button")
                        it.addEventListener("click", { copy() })
                    },
                )
            },
        )
        panel.appendChild(copied)
        panel.appendChild(
            element("p", "bring-claude-then").also { then ->
                then.appendChild(document.createTextNode("Then start "))
                then.appendChild(element("code", "").also { it.textContent = "claude" })
                then.appendChild(document.createTextNode(" in any folder and type "))
                then.appendChild(element("code", "").also { it.textContent = "/mcp__bee__whiteboard <topic>" })
                then.appendChild(document.createTextNode("."))
            },
        )
        if (command.viewer == Viewer.Unnamed) {
            panel.appendChild(
                element("p", "bring-claude-unnamed").also {
                    it.textContent = "This page wasn't opened with a name, so the line says “${McpCommand.PLACEHOLDER}”: put your own name there."
                },
            )
        }
        toggle.addEventListener("click", { show(panel.hidden) })
        document.addEventListener("keydown", { event ->
            if ((event as KeyboardEvent).key == "Escape" && !panel.hidden) show(false)
        })
    }

    /** A button that opens the panel, for a place other than the bar (the empty board's hint). */
    fun opener(): Element = element("span", "").also { offer ->
        offer.className = "bring-claude-offer"
        offer.appendChild(document.createTextNode("No session here yet? "))
        offer.appendChild(
            element("button", "").also {
                it.className = "bring-claude-open"
                it.setAttribute("type", "button")
                it.textContent = "Bring your Claude"
                it.addEventListener("click", { show(true) })
            },
        )
    }

    private fun heading(): Element = element("header", "bring-claude-head").also { head ->
        head.appendChild(element("strong", "").also { it.textContent = "Bring your Claude" })
        head.appendChild(
            element("button", "bring-claude-close").also {
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
        writeClipboard(command.line) { written ->
            copied.textContent = when {
                written || selectAndCopy(line) -> "Copied."
                else -> "Selected: press ${copyKeys()} to copy."
            }
        }
    }

    private companion object {
        const val PANEL_ID = "bring-claude"

        fun element(tag: String, id: String): Element = document.createElement(tag).also { if (id.isNotEmpty()) it.id = id }
    }
}

/** The keys that copy, on this viewer's platform. */
internal fun copyKeys(): String = if (window.navigator.platform.startsWith("Mac")) "⌘C" else "Ctrl+C"

/**
 * Writes [text] to the clipboard and calls [onDone] with whether it did. Without the async
 * clipboard (a plain-HTTP page that isn't loopback) it calls back at once, still inside the click,
 * so the fallback can copy the old way.
 */
@JsFun(
    """(text, onDone) => {
        const clipboard = window.isSecureContext ? navigator.clipboard : undefined;
        if (!clipboard) { onDone(false); return; }
        clipboard.writeText(text).then(() => onDone(true), () => onDone(false));
    }""",
)
internal external fun writeClipboard(text: String, onDone: (Boolean) -> Unit)

/** Selects [element]'s text, so the viewer can copy it themselves, and copies it if the browser still lets a page. */
@JsFun(
    """(element) => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        try { return document.execCommand('copy'); } catch (e) { return false; }
    }""",
)
internal external fun selectAndCopy(element: Element): Boolean
