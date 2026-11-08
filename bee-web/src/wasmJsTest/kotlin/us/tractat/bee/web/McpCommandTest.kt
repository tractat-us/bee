package us.tractat.bee.web

import kotlin.test.Test
import kotlin.test.assertEquals

/** The line "Bring your Claude" offers: this tab's key only when it holds one, and a shell-safe name. */
class McpCommandTest {
    private val origin = "http://192.168.1.20:8233"

    @Test
    fun aKeyedViewerGetsTheKeyAndTheirName() {
        assertEquals(
            "claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=s3cr3t&name=alex'",
            McpCommand.of(origin, Access.Keyed("s3cr3t"), Viewer.Named("alex")).line,
        )
    }

    @Test
    fun aLoopbackViewerGetsNoKey() {
        assertEquals(
            "claude mcp add --scope user --transport http bee 'http://127.0.0.1:8233/mcp?name=iain'",
            McpCommand.of("http://127.0.0.1:8233", Access.Loopback, Viewer.Named("iain")).line,
        )
    }

    @Test
    fun anUnnamedViewerGetsThePlaceholder() {
        assertEquals(
            "claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=s3cr3t&name=you'",
            McpCommand.of(origin, Access.Keyed("s3cr3t"), Viewer.Unnamed).line,
        )
    }

    /** A quote in a name would end the shell's single quote; `&` or a space would break the query. */
    @Test
    fun aNameCanNotBreakTheQuoteOrTheQuery() {
        assertEquals(
            "claude mcp add --scope user --transport http bee 'http://192.168.1.20:8233/mcp?k=a%2Bb&name=o%27brien%20%26%20co'",
            McpCommand.of(origin, Access.Keyed("a+b"), Viewer.Named("o'brien & co")).line,
        )
    }
}
