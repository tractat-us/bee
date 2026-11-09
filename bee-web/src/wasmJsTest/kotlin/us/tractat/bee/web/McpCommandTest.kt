package us.tractat.bee.web

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The lines "Bring your Claude" offers: a teammate's, with this tab's key, for every folder; the
 * owner's, the kit's bridge first and a URL for one folder only; every URL shell-safe.
 */
class McpCommandTest {
    private val origin = "http://192.168.1.20:8233"

    @Test
    fun aKeyedViewerGetsTheKeyAndTheirName() {
        assertEquals(
            "claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=s3cr3t&name=alex'",
            McpCommand.of(origin, Access.Keyed("s3cr3t"), Viewer.Named("alex")).line,
        )
    }

    /** On the host's own machine a user-scope bee would bring every `claude` there onto the board. */
    @Test
    fun aLoopbackViewerIsOfferedTheKitsBridgeFirstAndAUrlForOneFolder() {
        val command = assertIs<McpCommand.Owner>(McpCommand.of("http://127.0.0.1:8233", Access.Loopback, Viewer.Named("iain")))
        assertAll(
            { assertEquals("claude --mcp-config ~/.bee/mcp.json --allowedTools 'mcp__bee__*'", command.line) },
            { assertEquals("claude mcp add --transport http bee 'http://127.0.0.1:8233/mcp?name=iain'", command.folderLine) },
            { assertFalse("--scope user" in command.line + command.folderLine, "the owner is never offered --scope user") },
        )
    }

    @Test
    fun anUnnamedViewerGetsThePlaceholder() {
        assertAll(
            {
                assertEquals(
                    "claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=s3cr3t&name=you'",
                    McpCommand.of(origin, Access.Keyed("s3cr3t"), Viewer.Unnamed).line,
                )
            },
            {
                assertEquals(
                    "claude mcp add --transport http bee 'http://127.0.0.1:8233/mcp?name=you'",
                    McpCommand.Owner("http://127.0.0.1:8233", Viewer.Unnamed).folderLine,
                )
            },
        )
    }

    /** A quote in a name would end the shell's single quote; `&` or a space would break the query. */
    @Test
    fun aNameCanNotBreakTheQuoteOrTheQuery() {
        val name = Viewer.Named("o'brien & co")
        assertAll(
            {
                assertEquals(
                    "claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=a%2Bb&name=o'\\''brien%20%26%20co'",
                    McpCommand.of(origin, Access.Keyed("a+b"), name).line,
                )
            },
            {
                assertEquals(
                    "claude mcp add --transport http bee 'http://127.0.0.1:8233/mcp?name=o'\\''brien%20%26%20co'",
                    McpCommand.Owner("http://127.0.0.1:8233", name).folderLine,
                )
            },
        )
    }

    /**
     * A browser keeps `'`, `;` and `$(` in a host name (`new URL("http://a'b.example/").origin`), so
     * the origin is quoted with the rest: each `'` closes the quote, adds an escaped `'`, and reopens
     * it, and the `;` and `$(` stay inside quotes, one inert word.
     */
    @Test
    fun anOriginCanNotBreakTheQuote() {
        val hostile = "http://a';\$(touch x).example:8233"
        assertAll(
            {
                assertEquals(
                    "claude mcp remove --scope user bee >/dev/null 2>&1; claude mcp add --scope user --transport http bee 'http://a'\\'';$(touch x).example:8233/mcp?k=s3cr3t&name=alex'",
                    McpCommand.of(hostile, Access.Keyed("s3cr3t"), Viewer.Named("alex")).line,
                )
            },
            {
                assertEquals(
                    "claude mcp add --transport http bee 'http://a'\\'';$(touch x).example:8233/mcp?name=alex'",
                    assertIs<McpCommand.Owner>(McpCommand.of(hostile, Access.Loopback, Viewer.Named("alex"))).folderLine,
                )
            },
        )
    }
}
