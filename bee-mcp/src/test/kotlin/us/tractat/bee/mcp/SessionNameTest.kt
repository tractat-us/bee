package us.tractat.bee.mcp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How [HttpBridges] names a session over HTTP: who it is for, then the tool its client is (bee#224). */
class SessionNameTest {
    @Test
    fun aSessionIsNamedForItsPersonThenItsTool() = assertAll(
        { assertEquals("sam-claude", HttpBridges.sessionNameFrom("sam", AgentTool.of("claude-code")), "Claude Code's name is unchanged") },
        { assertEquals("sam-codex", HttpBridges.sessionNameFrom("sam", AgentTool.of("codex-mcp-client"))) },
        { assertEquals("sam-agent", HttpBridges.sessionNameFrom("sam", AgentTool.of("some-editor"))) },
        { assertEquals("sam-agent", HttpBridges.sessionNameFrom("sam", AgentTool.of(null)), "a client that gave no name") },
        { assertEquals("codex", HttpBridges.sessionNameFrom(null, AgentTool.Codex), "no name: the tool alone") },
        { assertEquals("claude", HttpBridges.sessionNameFrom("  ", AgentTool.Claude), "a blank name is none") },
        { assertEquals("sam-claude", HttpBridges.sessionNameFrom(" s:a#m ", AgentTool.Claude), "':' and '#' dropped") },
    )

    @Test
    fun aClientsToolIsReadLooselyFromItsName() = assertAll(
        { assertEquals(AgentTool.Claude, AgentTool.of("Claude-Code")) },
        { assertEquals(AgentTool.Codex, AgentTool.of("Codex")) },
        { assertEquals(AgentTool.Other, AgentTool.of("")) },
    )

    /** No name, or the page's placeholder, is nobody's, whichever tool presents it. */
    @Test
    fun onlyAChosenNameOnADevicesKeyResumes() = assertAll(
        { assertTrue(HttpBridges.resumable("sam", PresentedKey.Device)) },
        { assertTrue(HttpBridges.resumable("sam", PresentedKey.None)) },
        { assertFalse(HttpBridges.resumable("sam", PresentedKey.Join), "the join link's key is everyone's") },
        { assertFalse(HttpBridges.resumable(null, PresentedKey.Device), "no name") },
        { assertFalse(HttpBridges.resumable(" ", PresentedKey.Device), "a blank name") },
        { assertFalse(HttpBridges.resumable(HttpBridges.PLACEHOLDER_NAME, PresentedKey.Device), "the page's placeholder") },
    )

    @Test
    fun theClientsNameIsReadFromInitialize() = assertAll(
        {
            val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"codex-mcp-client","version":"0"}}}"""
            assertEquals("codex-mcp-client", HttpBridges.clientNameIn(body))
        },
        { assertNull(HttpBridges.clientNameIn("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""), "no clientInfo") },
        { assertNull(HttpBridges.clientNameIn("""{"params":{"clientInfo":{"name":7}}}"""), "a name that isn't a string") },
        { assertNull(HttpBridges.clientNameIn("""[{"params":{}}]"""), "not one message") },
        { assertNull(HttpBridges.clientNameIn("not json"), "not JSON") },
    )
}
