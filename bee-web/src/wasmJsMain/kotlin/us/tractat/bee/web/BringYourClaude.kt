package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.KeyboardEvent

/** Who views this page: a person `?name=` names, or one the page can't name. */
internal sealed interface Viewer {
    data class Named(val name: String) : Viewer

    data object Unnamed : Viewer
}

/**
 * What points a viewer's own Claude session at this board, by who the viewer is to it: a
 * [Teammate] on a machine of their own, holding a key, or the board's [Owner] on its own machine.
 *
 * Every URL a line carries is one single-quoted shell word, every `'` in it escaped: a browser
 * keeps `'`, `;` and `$(` in a host name, so neither the origin nor a name can end the quote.
 */
internal sealed interface McpCommand {
    /** The line the panel offers first. */
    val line: String

    val viewer: Viewer

    /**
     * A viewer holding [key], a join link's or a paired device's: bee-host's MCP endpoint at
     * [PATH] on [origin], with the key and the viewer's name.
     *
     * `--scope user` because the session it brings may start in any folder: `claude mcp add`'s own
     * default scope is the folder it runs in. The line first removes any earlier `bee` entry,
     * quietly, since `claude mcp add` refuses a name it already has: one line still works with a
     * new key or for another board.
     */
    class Teammate(origin: String, key: String, override val viewer: Viewer) : McpCommand {
        override val line = "$REMOVE_EARLIER claude mcp add --scope user --transport http bee ${endpoint(origin, listOf("k" to key), viewer)}"
    }

    /**
     * The board's owner, on the host's own machine, where the page needs no key. Their session
     * starts on the kit's stdio bridge, with the [line] `bee-up` printed: that bridge keeps its
     * identity across a host restart, and an HTTP session doesn't (#126). The page can't know
     * `BEE_HOME`, so the line names its default.
     *
     * [folderLine] is the by-URL way, for one folder only. Never `--scope user` here: every
     * `claude` started on this machine, dispatched workers too, would join the board and stay on
     * it as a ghost for two minutes after it exits.
     */
    class Owner(origin: String, override val viewer: Viewer) : McpCommand {
        override val line = KIT_LINE

        val folderLine = "claude mcp add --transport http bee ${endpoint(origin, emptyList(), viewer)}"
    }

    companion object {
        /** bee-host's MCP Streamable HTTP route. */
        const val PATH = "/mcp"

        /** The name a URL carries for a viewer the page can't name. */
        const val PLACEHOLDER = "you"

        /** The line `bee-up` prints for the owner's own session, at the default `BEE_HOME`. */
        const val KIT_LINE = "claude --mcp-config ~/.bee/mcp.json --allowedTools 'mcp__bee__*'"

        /** Drops an earlier `bee` entry, saying nothing whether or not there was one. */
        private const val REMOVE_EARLIER = "claude mcp remove --scope user bee >/dev/null 2>&1;"

        fun of(origin: String, access: Access.Admitted, viewer: Viewer): McpCommand = when (access) {
            is Access.Keyed -> Teammate(origin, access.key, viewer)
            Access.Loopback -> Owner(origin, viewer)
        }

        /** bee-host's endpoint on [origin], with [params] and the viewer's name, as one quoted shell word. */
        private fun endpoint(origin: String, params: List<Pair<String, String>>, viewer: Viewer): String {
            val name = when (viewer) {
                is Viewer.Named -> viewer.name
                Viewer.Unnamed -> PLACEHOLDER
            }
            val query = (params + ("name" to name)).joinToString("&") { (param, value) -> "$param=${encodeURIComponent(value)}" }
            return singleQuoted("$origin$PATH?$query")
        }

        /** [text] as one inert POSIX shell word: single quotes, each `'` in it closed, escaped and reopened. */
        private fun singleQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"
    }
}

/**
 * "Bring your Claude": a control in the page's bar that opens a panel with [command], to copy and
 * run in a terminal, then how to start the session it brings. A teammate gets one line; the owner
 * gets the kit's line, then the one-folder URL line.
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
 * `#bring-claude-command`, `#bring-claude-copy`, `#bring-claude-copied`, for the owner
 * `#bring-claude-folder-command` and `#bring-claude-folder-copy`, and, for an unnamed viewer,
 * `#bring-claude-unnamed`; [opener] makes a `.bring-claude-open` button for elsewhere.
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
    private val copied: Element = element("p", "bring-claude-copied").also { it.setAttribute("aria-live", "polite") }

    init {
        panel.appendChild(heading())
        when (command) {
            is McpCommand.Teammate -> teammate(command)
            is McpCommand.Owner -> owner(command)
        }
        if (command.viewer == Viewer.Unnamed) {
            panel.appendChild(
                element("p", "bring-claude-unnamed").also {
                    it.textContent = "This page wasn't opened with a name, so the URL says “${McpCommand.PLACEHOLDER}”: put your own name there."
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

    /** A teammate's machine: the one line that adds bee for every folder, then start `claude` anywhere. */
    private fun teammate(command: McpCommand.Teammate) {
        panel.appendChild(element("p", "").also { it.textContent = "In a terminal, run this. It replaces any earlier bee entry." })
        panel.appendChild(lineRow(command.line, PRIMARY))
        panel.appendChild(copied)
        panel.appendChild(
            then(
                document.createTextNode("Then start "),
                element("code", "").also { it.textContent = "claude" },
                document.createTextNode(" in any folder and type "),
                whiteboard(),
                document.createTextNode("."),
            ),
        )
    }

    /** The board's own machine: the kit's line starts the session; a URL for one folder is the other way. */
    private fun owner(command: McpCommand.Owner) {
        panel.appendChild(
            element("p", "").also {
                it.textContent = "On the board's own laptop, start Claude with the line bee-up printed. Unless you set BEE_HOME, it's this one:"
            },
        )
        panel.appendChild(lineRow(command.line, PRIMARY))
        panel.appendChild(then(document.createTextNode("Then type "), whiteboard(), document.createTextNode(".")))
        panel.appendChild(
            element("p", "bring-claude-or").also {
                it.textContent = "Or add bee by URL for one folder only: run this in the folder you'll start claude from."
            },
        )
        panel.appendChild(lineRow(command.folderLine, FOLDER))
        panel.appendChild(copied)
    }

    /** [text] in a `<code>` with its own Copy button, under the ids [ids] names. */
    private fun lineRow(text: String, ids: LineIds): Element = element("div", ids.row).also { row ->
        val code = element("code", ids.code).also { it.textContent = text }
        row.appendChild(code)
        row.appendChild(
            element("button", ids.copy).also {
                it.textContent = "Copy"
                it.setAttribute("type", "button")
                it.addEventListener("click", { copy(text, code) })
            },
        )
    }

    private fun then(vararg parts: Node): Element = element("p", "bring-claude-then").also { then -> parts.forEach(then::appendChild) }

    private fun whiteboard(): Element = element("code", "").also { it.textContent = "/mcp__bee__whiteboard <topic>" }

    private fun show(open: Boolean) {
        panel.hidden = !open
        toggle.setAttribute("aria-expanded", open.toString())
        if (!open) copied.textContent = ""
    }

    private fun copy(text: String, code: Element) {
        writeClipboard(text) { written ->
            copied.textContent = when {
                written || selectAndCopy(code) -> "Copied."
                else -> "Selected: press ${copyKeys()} to copy."
            }
        }
    }

    /** The ids of one copyable line: its row, its `<code>` and its Copy button. */
    private class LineIds(val row: String, val code: String, val copy: String)

    private companion object {
        const val PANEL_ID = "bring-claude"

        /** The line the panel offers first. */
        val PRIMARY = LineIds("bring-claude-line", "bring-claude-command", "bring-claude-copy")

        /** The owner's by-URL line, for one folder. */
        val FOLDER = LineIds("bring-claude-folder-line", "bring-claude-folder-command", "bring-claude-folder-copy")

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
