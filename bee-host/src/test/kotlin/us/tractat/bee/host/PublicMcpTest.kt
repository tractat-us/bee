package us.tractat.bee.host

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PeerKind
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.bee.mcp.BeeTools
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A Claude reaching a public host over MCP Streamable HTTP, as the owner's `claude mcp add
 * --transport http bee "https://bee.tractat.us/mcp?k=<owner key>"` does (bee#145). Being on
 * loopback grants nothing there, so the host's own bridge for the session dials the board with the
 * host's bridge key ([HttpBridges][us.tractat.bee.mcp.HttpBridges]); a session that draws on the
 * board proves it got in.
 *
 * Every dial here comes from loopback with no forwarded header, as the host's own processes do: a
 * call that gets in here would get in from anywhere.
 */
class PublicMcpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("w1-public-mcp-")
    private val http = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun theOwnerKeyAdmitsMcpAndItsBridgeReachesTheBoard() = runBlocking {
        val host = BeeHost.start(scope, fakePublicHost(dir))
        try {
            val keyless = initialize(mcp(host, "?name=eve"))
            val claude = connect(mcp(host, "?name=iain&k=$FAKE_OWNER_KEY"))
            val opener = drawAndLearnWho(claude, host, "from-the-owner")
            claude.close()
            assertAll(
                { assertEquals(HttpStatusCode.Forbidden, keyless, "no key, from loopback: refused before MCP") },
                { assertEquals(PeerKind.Agent, opener.kind, "drawn by the session's bridge") },
                { assertEquals("iain-claude", opener.name) },
            )
        } finally {
            host.close()
        }
    }

    /**
     * bee#126 on a hosted board: the owner's Claude, back after a restart at the same URL, takes
     * its kept identity, and that resumed bridge still reaches the board. Its sockets carry this
     * boot's bridge key as a fresh bridge's do, or a public host, where loopback grants nothing,
     * would refuse every one of its dials.
     */
    @Test
    fun aKeptSessionResumedAfterARestartReachesTheBoard() = runBlocking {
        val query = "?name=iain&k=$FAKE_OWNER_KEY"
        val first = BeeHost.start(scope, fakePublicHost(dir))
        val before = try {
            drawAndLearnWho(connect(mcp(first, query)), first, "iain-plan")
        } finally {
            first.close()
        }
        val host = BeeHost.start(scope, fakePublicHost(dir))
        try {
            val claude = connect(mcp(host, query))
            val after = drawAndLearnWho(claude, host, "iain-after")
            claude.close()
            assertEquals(before, after, "the kept identity, drawing on the board after the restart")
        } finally {
            host.close()
        }
    }

    /** The owner key lives in memory, never in the data dir, so forgetting every device keeps it. */
    @Test
    fun theOwnerKeySurvivesForgetDevices() = runBlocking {
        val host = BeeHost.start(scope, fakePublicHost(dir, "--forget-devices"))
        try {
            val board = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}&k=$FAKE_OWNER_KEY"
            val keyFile = Files.readString(dir.resolve(KeyFile.NAME))
            assertAll(
                { assertEquals(SWITCHING, Handshakes(host.port).upgrade(board)) },
                { assertFalse(FAKE_OWNER_KEY in keyFile, "never saved") },
                { assertFalse(KeyHash.of(FAKE_OWNER_KEY).value in keyFile, "nor its hash") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * The host keeps no access log, so no key in a URL reaches its log: not the owner's on `/mcp`,
     * nor a paired device's on the board and claims sockets, nor the host's own bridge key on its
     * bridges' dials. Every event the host's loggers emit across a keyed session and keyed sockets
     * is read, at the levels the host ships with.
     */
    @Test
    fun noKeyReachesTheHostsLog() = runBlocking {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(logged)
        try {
            val host = BeeHost.start(scope, fakePublicHost(dir))
            val bridgeKey = host.bridgeKey.value
            val paired = try {
                val ticket = assertIs<Opened.Ticket>(host.pairing.open(Caller.forwarded("203.0.113.9")))
                val key = assertIs<Attempt.Admitted>(host.pairing.attempt(ticket.id, PeerIdentity.human("iain"), ticket.code)).key.value
                val calls = Handshakes(host.port)
                val sockets = listOf(BoardRelay.PATH, BeeHost.CLAIMS_PATH).flatMap { path ->
                    listOf(FAKE_OWNER_KEY, key).map { calls.upgrade("$path?peer=probe&${BoardId.PARAM}=${host.boardId}&k=$it") }
                }
                check(sockets.all { it == SWITCHING }) { "rig: every keyed socket opened: $sockets" }
                val claude = connect(mcp(host, "?name=iain&k=$FAKE_OWNER_KEY"))
                drawAndLearnWho(claude, host, "logged")
                claude.close()
                key
            } finally {
                host.close()
            }
            val lines = logged.list.map { event -> event.formattedMessage + event.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty() }
            val leaks = lines.filter { line -> listOf(FAKE_OWNER_KEY, paired, bridgeKey).any { it in line } }
            assertAll(
                { assertTrue(lines.isNotEmpty(), "rig: the appender heard the host") },
                { assertEquals(emptyList(), leaks) },
            )
        } finally {
            root.detachAppender(logged)
        }
    }

    private fun mcp(host: BeeHost, query: String) = "http://127.0.0.1:${host.port}${BeeHost.MCP_PATH}$query"

    private suspend fun connect(url: String): Client =
        Client(Implementation("test", "0")).apply { connect(StreamableHttpClientTransport(http, url)) }

    /** A raw `initialize` POST: the status the host answers it with. */
    private suspend fun initialize(url: String): HttpStatusCode = http.post(url) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"raw","version":"0"}}}""",
        )
    }.status

    /** Opens [surface] as [claude], and returns who the board says opened it. */
    private suspend fun drawAndLearnWho(claude: Client, host: BeeHost, surface: String): PeerIdentity {
        val opened = claude.callTool(BeeTools.OPEN, openArgs(surface))
        check(opened.isError != true) { "the open failed: ${opened.content}" }
        val board = withTimeout(TIMEOUT) { host.state.first { surface in it.openSurfaces() } }
        return requireNotNull(board.meta(surface)).openedBy
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
    }
}
