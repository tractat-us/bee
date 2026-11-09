package us.tractat.bee.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.telemetry.Attr
import us.tractat.bee.telemetry.BeeTelemetry
import us.tractat.bee.telemetry.Role
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Each tool call is a span: its name, peer, ids, duration and outcome, and never its arguments' content. */
class ToolTelemetryTest {
    private class Span(val name: String, val duration: kotlin.time.Duration, val attributes: Map<String, String>, val error: String?)

    private class Spans : BeeTelemetry {
        val seen = mutableListOf<Span>()

        override fun event(name: String, attributes: Map<String, String>) = Unit

        override fun span(name: String, start: Instant, end: Instant, attributes: Map<String, String>, error: String?) {
            seen += Span(name, end - start, attributes, error)
        }
    }

    /** Ticks 25 ms each time it's read, so a call's span spans exactly one tick. */
    private class Ticking : Clock {
        private var now = Instant.fromEpochSeconds(1_000)

        override fun now(): Instant = now.also { now += 25.milliseconds }
    }

    @Test
    fun aToolCallIsASpanWithItsIdsAndNoContent() = runTest {
        val spans = Spans()
        val ada = PeerIdentity.agent("ada", Random(1))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server = Server(
            Implementation("bee", "test"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)), handlerCoroutineContext = dispatcher),
        )
        server.addTool(name = "open", description = "opens") { CallToolResult(content = listOf(TextContent("ok"))) }
        server.addTool(name = "refuse", description = "refuses") { CallToolResult(content = listOf(TextContent("no")), isError = true) }
        server.recordToolCalls(spans, Ticking()) { ada }
        val (clientSide, serverSide) = LinkedTransport.pair()
        server.createSession(serverSide)
        val client = Client(Implementation("test", "test"), ClientOptions(handlerCoroutineContext = dispatcher)).also { it.connect(clientSide) }

        val opened = client.callTool("open", mapOf("surfaceId" to "lunch", "title" to "secret title", "components" to listOf(mapOf("text" to "secret text"))))
        client.callTool("refuse", mapOf("surfaceId" to "lunch"))

        val (open, refuse) = spans.seen
        assertAll(
            { assertNull(opened?.isError, "the call's own result passes through") },
            { assertEquals("mcp.tool", open.name) },
            { assertEquals(mapOf(Attr.TOOL to "open", Attr.ROLE to Role.BRIDGE, Attr.PEER to ada.toString(), Attr.SURFACE to "lunch", Attr.OUTCOME to "ok"), open.attributes) },
            { assertEquals(25.milliseconds, open.duration) },
            { assertNull(open.error) },
            { assertEquals("error", refuse.attributes[Attr.OUTCOME]) },
            { assertEquals("error", refuse.error) },
            { assertTrue(spans.seen.none { span -> span.attributes.values.any { "secret" in it } }, "an argument's content was kept") },
        )
    }
}
