package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.path
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.Departure
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.mcp.BeeTools
import us.tractat.bee.mcp.CheckinGuide
import us.tractat.bee.mcp.HttpBridges
import us.tractat.bee.mcp.WhiteboardGuide
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Claude with nothing installed, reaching a real [BeeHost] over MCP Streamable HTTP at
 * [BeeHost.MCP_PATH]: the SDK's own HTTP client, as Claude Code's is, over CIO. The host runs a
 * bridge for each session ([HttpBridges]), which joins the board as any bridge does; the tests
 * read what it did off the host's own board.
 */
class McpHttpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-mcp-http-")
    private val http = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aClaudeOverHttpGetsTheBridgesToolsPromptAndInstructionsAndDrawsOnTheBoard() = runBlocking {
        val host = startHost()
        try {
            val claude = connect(url(host, "?name=ada"))
            val tools = claude.listTools().tools.map { it.name }
            val prompts = claude.listPrompts().prompts.map { it.name }
            val opened = claude.callTool(BeeTools.OPEN, openArgs("lunch"))
            val board = withTimeout(TIMEOUT) { host.state.first { "lunch" in it.openSurfaces() } }
            val opener = board.meta("lunch")?.openedBy
            assertAll(
                { assertTrue(BeeTools.BOARD in tools && BeeTools.OPEN in tools, "the bridge's tools: $tools") },
                { assertTrue(WhiteboardGuide.PROMPT in prompts && CheckinGuide.PROMPT in prompts, "stdio's prompts: $prompts") },
                { assertEquals(WhiteboardGuide.INSTRUCTIONS, claude.serverInstructions, "the bridge's instructions, in initialize") },
                { assertFalse(opened.isError == true, "the open succeeded: ${opened.content}") },
                { assertEquals(PeerKind.Agent, opener?.kind, "opened by an agent") },
                { assertEquals("ada-claude", opener?.name, "named for ?name=, as bee-up names a session") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    @Test
    fun deletingTheSessionDepartsItsBridgeLeft() = runBlocking {
        val host = startHost()
        try {
            val transport = StreamableHttpClientTransport(http, url(host, "?name=bo"))
            val claude = Client(Implementation("test", "0")).apply { connect(transport) }
            val who = drawAndLearnWho(claude, host, "bo-surface")
            transport.terminateSession()
            val departed = withTimeout(TIMEOUT) { departureOf(host, who) }
            val left = withTimeout(TIMEOUT) { awaitCount(host, 0) }
            assertAll(
                { assertEquals(DepartReason.Left, departed.reason, "a DELETE is a goodbye") },
                { assertEquals(0, left, "the host holds no bridge for it") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    /**
     * A client that only checks the server, as `claude mcp list` does, deletes its session within
     * milliseconds, before its bridge's claims link is up: it still departs `Left`, never left for
     * the host to find.
     */
    @Test
    fun aSessionDeletedAtOnceStillDepartsLeft() = runBlocking {
        val host = startHost()
        try {
            val url = url(host, "?name=flo")
            val opened = http.post(url) { initializeBody() }
            val session = requireNotNull(opened.headers[SESSION_HEADER]) { "rig: initialize answers a session: ${opened.status}" }
            val deleted = http.delete(url) { header(SESSION_HEADER, session) }.status
            val departed = withTimeout(TIMEOUT) {
                host.state.first { state -> state.departures().any { it.identity.name == "flo-claude" } }
                    .departures().first { it.identity.name == "flo-claude" }
            }
            assertAll(
                { assertEquals(HttpStatusCode.OK, deleted, "rig: the DELETE closed the session") },
                { assertEquals(DepartReason.Left, departed.reason, "departed as a goodbye") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * Claude Code's first `initialize` names a protocol version newer than this SDK's, which the
     * SDK answers 400 before Claude Code retries. That session never initialized, so its bridge
     * never reaches the board: no presence, and no departure, when it closes.
     */
    @Test
    fun aSessionThatNeverInitializesLeavesNoTrace() = runBlocking {
        val host = startHost()
        try {
            val refused = http.post(url(host, "?name=gus")) {
                initializeBody()
                header("MCP-Protocol-Version", "2999-01-01")
            }.status
            val gone = withTimeout(TIMEOUT) { awaitCount(host, 0) }
            // The control: a session that does initialize shows, so a trace of gus would have.
            val claude = connect(url(host, "?name=hal"))
            drawAndLearnWho(claude, host, "hal-surface")
            withTimeout(TIMEOUT) { host.state.first { state -> state.latestPresence().any { it.identity.name == "hal-claude" } } }
            val state = host.state.value
            assertAll(
                { assertEquals(HttpStatusCode.BadRequest, refused, "rig: the SDK refuses the version") },
                { assertEquals(0, gone, "the host keeps no bridge for it") },
                { assertTrue(state.latestPresence().none { it.identity.name == "gus-claude" }, "never on the roster") },
                { assertTrue(state.departures().none { it.identity.name == "gus-claude" }, "never departed") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    /**
     * A session with its GET stream open never idles, however long it goes without a request; once
     * the client goes without a DELETE, as a killed Claude does, its bridge departs `Quiet`.
     */
    @Test
    fun aSessionIdlesOnlyOnceItsStreamIsGoneThenDepartsQuiet() = runBlocking {
        val host = startHost(idleAfter = IDLE)
        try {
            val claude = connect(url(host, "?name=cy"))
            val who = drawAndLearnWho(claude, host, "cy-surface")
            delay(IDLE * 4)
            val whileOpen = host.state.value.departures().firstOrNull { it.identity == who }
            claude.close()
            val departed = withTimeout(TIMEOUT) { departureOf(host, who) }
            assertAll(
                { assertEquals(null, whileOpen, "an open GET stream keeps the session, past four idle windows") },
                { assertEquals(DepartReason.Quiet, departed.reason, "gone without a goodbye: the host judged it quiet") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * Off the laptop, `/mcp` takes a key, as the board's sockets do: the host reads each call to
     * `/mcp` as from [ANOTHER_MACHINE], while its own bridges' sockets stay on loopback.
     */
    @Test
    fun offTheLaptopMcpNeedsAKeyTheHostMinted() = runBlocking {
        val host = startHost(peers = { call -> if (call.request.path().startsWith(BeeHost.MCP_PATH)) ANOTHER_MACHINE else "127.0.0.1" })
        try {
            val keyless = initialize(url(host, "?name=eve"))
            val strangerKey = initialize(url(host, "?name=eve&k=not-a-key"))
            val claude = connect(url(host, "?name=dee&k=${host.pairing.joinKey.value}"))
            val tools = claude.listTools().tools.map { it.name }
            assertAll(
                { assertEquals(HttpStatusCode.Forbidden, keyless, "no key: refused before MCP") },
                { assertEquals(HttpStatusCode.Forbidden, strangerKey, "a key this host never minted: refused") },
                { assertTrue(BeeTools.BOARD in tools, "the join link's key admits: $tools") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    private suspend fun startHost(idleAfter: Duration = HttpBridges.IDLE_AFTER, peers: PeerAddress = PeerAddress.DIRECT) =
        BeeHost.start(scope, HostConfig(port = 0, dataDir = dir, mcpIdleAfter = idleAfter), peers)

    private fun url(host: BeeHost, query: String) = "http://127.0.0.1:${host.port}${BeeHost.MCP_PATH}$query"

    private suspend fun connect(url: String): Client =
        Client(Implementation("test", "0")).apply { connect(StreamableHttpClientTransport(http, url)) }

    /** Opens [surface] as [claude], and returns who the board says opened it. */
    private suspend fun drawAndLearnWho(claude: Client, host: BeeHost, surface: String): PeerIdentity {
        val opened = claude.callTool(BeeTools.OPEN, openArgs(surface))
        check(opened.isError != true) { "rig: the open failed: ${opened.content.filterIsInstance<TextContent>().map { it.text }}" }
        val board = withTimeout(TIMEOUT) { host.state.first { surface in it.openSurfaces() } }
        return requireNotNull(board.meta(surface)).openedBy
    }

    private suspend fun departureOf(host: BeeHost, who: PeerIdentity): Departure =
        host.state.first { state -> state.departures().any { it.identity == who } }.departures().first { it.identity == who }

    private suspend fun awaitCount(host: BeeHost, n: Int): Int {
        while (host.bridges.count != n) delay(50)
        return n
    }

    /** A raw `initialize` POST: the status the host answers it with. */
    private suspend fun initialize(url: String): HttpStatusCode = http.post(url) { initializeBody() }.status

    private fun HttpRequestBuilder.initializeBody() {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"raw","version":"0"}}}""",
        )
    }

    private fun openArgs(id: String) = mapOf(
        "surfaceId" to id,
        "title" to "Surface $id",
        "components" to JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "root")
                    put("component", "Column")
                    put("children", JsonArray(listOf(JsonPrimitive("hello"))))
                },
                buildJsonObject {
                    put("id", "hello")
                    put("component", "Text")
                    put("text", "Hello")
                },
            ),
        ),
    )

    private companion object {
        val TIMEOUT = 20.seconds
        val IDLE = 1.seconds
        const val ANOTHER_MACHINE = "192.168.1.20"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
