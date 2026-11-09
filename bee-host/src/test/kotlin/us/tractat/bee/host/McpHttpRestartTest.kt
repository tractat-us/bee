package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.mcp.BeeTools
import us.tractat.bee.mcp.BridgePeer
import us.tractat.bee.mcp.HttpBridges
import us.tractat.bee.mcp.KeptSessionFile
import us.tractat.bee.mcp.peerHttpClient
import us.tractat.kuilt.test.assertAll
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A Claude joined over HTTP, across a host restart on the same data dir (bee#126). The host's MCP
 * sessions are in memory, so Claude Code initializes a new one at the same URL; the host hands
 * that session the identity its last one held, so the surfaces it opened are still its own: its
 * waits cover them, and their cards aren't muted, since nothing departed it.
 */
class McpHttpRestartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("bee-mcp-restart-")
    private val http = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun stop() {
        http.close()
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aClaudeOverHttpComesBackFromAHostRestartAsItself() = runBlocking {
        val before = openThenRestart("?name=andrew", "andrew-plan")
        val host = startHost()
        try {
            val claude = connect(url(host, "?name=andrew"))
            val after = drawAndLearnWho(claude, host, "andrew-after")
            val state = host.state.value
            assertAll(
                { assertEquals(before, after, "the same identity, so the surfaces it opened are still its own") },
                { assertTrue(state.departures().none { it.identity == before }, "never departed, so its cards aren't muted: ${state.departures()}") },
                { assertEquals(before, state.meta("andrew-plan")?.openedBy, "rig: the old surface survived the restart") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    /** Another key at the same name is someone else: they never get andrew's identity. */
    @Test
    fun anotherKeyWithTheSameNameGetsAnIdentityOfItsOwn() = runBlocking {
        val before = openThenRestart("?name=andrew", "andrew-plan")
        val host = startHost()
        try {
            // On loopback the host admits any key, so the key here only names whose Claude it is.
            val stranger = connect(url(host, "?name=andrew&k=someone-else"))
            val strangers = drawAndLearnWho(stranger, host, "stranger-surface")
            val andrew = connect(url(host, "?name=andrew"))
            val andrews = drawAndLearnWho(andrew, host, "andrew-after")
            assertAll(
                { assertNotEquals(before, strangers, "another key doesn't inherit andrew's surfaces") },
                { assertEquals(before, andrews, "control: andrew's own URL still does") },
            )
            stranger.close()
            andrew.close()
        } finally {
            host.close()
        }
    }

    /** One identity, one session: a second session at the same URL never takes the first's surfaces. */
    @Test
    fun aSecondSessionAtTheSameUrlNeverTakesTheFirstsIdentity() = runBlocking {
        val before = openThenRestart("?name=andrew", "andrew-plan")
        val host = startHost()
        try {
            val first = connect(url(host, "?name=andrew"))
            val firsts = drawAndLearnWho(first, host, "first-after")
            val second = connect(url(host, "?name=andrew"))
            val seconds = drawAndLearnWho(second, host, "second-after")
            assertAll(
                { assertEquals(before, firsts, "rig: the first session back comes back as andrew") },
                { assertNotEquals(firsts, seconds, "the second, while the first is live, is someone new") },
            )
            first.close()
            second.close()
        } finally {
            host.close()
        }
    }

    /**
     * The demo's setup: the presenter's stdio bridge stays connected across the restart, and its
     * first-contact push brings the old host's presence slot for andrew back, old claims node and
     * all. The voter drops that node a learner grace after boot; andrew, live on a new node, must
     * not be departed for it.
     */
    @Test
    fun aPeerThatSurvivesTheRestartNeverGetsTheResumedIdentityDeparted() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val first = startHost(port)
        val presenter = BridgePeer.join(HostAddress.parse("ws://127.0.0.1:$port"), "presenter", peerHttpClient(), scope, Random(9), { Clock.System.now() })
        presenter.start()
        val before = drawAndLearnWho(connect(url(first, "?name=andrew")), first, "andrew-plan")
        withTimeout(TIMEOUT) { presenter.board.state.first { s -> s.latestPresence().any { it.identity == before && it.claimsNode != null } } }
        first.close()
        val host = startHost(port)
        try {
            val claude = connect(url(host, "?name=andrew"))
            val after = drawAndLearnWho(claude, host, "andrew-after")
            // The rig: both slots are on the new board, the pushed-back one and the resumed one.
            withTimeout(TIMEOUT) { host.state.first { s -> s.latestPresence().count { it.identity == before } >= 2 } }
            val departed = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2 + 10.seconds) {
                host.state.first { s -> s.departures().any { it.identity == before } }
            }
            assertAll(
                { assertEquals(before, after, "rig: andrew came back as itself") },
                { assertEquals(null, departed?.departures()?.filter { it.identity == before }, "andrew, live, was never departed") },
            )
            claude.close()
        } finally {
            presenter.board.close()
            host.close()
        }
    }

    /**
     * Everyone holding the join link holds its key, so a session on it never comes back as a kept
     * identity, whatever name it chose. A paired device's own key does: the key is that device's.
     */
    @Test
    fun onlyAPairedDevicesOwnKeyComesBackAsItselfNeverTheJoinLinks() = runBlocking {
        val first = startHost()
        val ticket = assertIs<Opened.Ticket>(first.pairing.open(ANOTHER_MACHINE))
        val device = assertIs<Attempt.Admitted>(first.pairing.attempt(ticket.id, PeerIdentity.human("andrew"), ticket.code)).key.value
        val join = first.pairing.joinKey.value
        val (onDevice, onJoin) = try {
            drawAndLearnWho(connect(url(first, "?name=andrew&k=$device")), first, "device-plan") to
                drawAndLearnWho(connect(url(first, "?name=bea&k=$join")), first, "join-plan")
        } finally {
            first.close()
        }
        val host = startHost()
        try {
            val onDeviceAgain = drawAndLearnWho(connect(url(host, "?name=andrew&k=$device")), host, "device-after")
            val onJoinAgain = drawAndLearnWho(connect(url(host, "?name=bea&k=$join")), host, "join-after")
            assertAll(
                { assertEquals(onDevice, onDeviceAgain, "a paired device's key, a chosen name: the same identity") },
                { assertNotEquals(onJoin, onJoinAgain, "the join link's key is everyone's: a new identity, though bea chose her name") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * Everyone who pastes the page's line unedited is `name=you` on the one join link, and a URL
     * with no name is anybody's: neither name comes back as the identity it had.
     */
    @Test
    fun aNameNobodyChoseNeverComesBackAsItsOldIdentity() = runBlocking {
        val first = startHost()
        val (placeholder, nameless) = try {
            drawAndLearnWho(connect(url(first, "?name=${HttpBridges.PLACEHOLDER_NAME}")), first, "you-plan") to
                drawAndLearnWho(connect(url(first, "")), first, "anon-plan")
        } finally {
            first.close()
        }
        val host = startHost()
        try {
            val placeholderAgain = drawAndLearnWho(connect(url(host, "?name=${HttpBridges.PLACEHOLDER_NAME}")), host, "you-after")
            val namelessAgain = drawAndLearnWho(connect(url(host, "")), host, "anon-after")
            assertAll(
                { assertEquals("you-claude", placeholder.name, "rig: the page's placeholder name") },
                { assertNotEquals(placeholder, placeholderAgain, "name=you is anybody's") },
                { assertNotEquals(nameless, namelessAgain, "no name is anybody's") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * A kept session whose client never comes back, with no peer left to post for it: the host
     * departs it `Quiet` once its window passes, and forgets it, so its card isn't Away forever and
     * its URL isn't left ambiguous.
     */
    @Test
    fun aKeptSessionWhoseClientNeverComesBackDepartsQuietAndIsForgotten() = runBlocking {
        val before = openThenRestart("?name=andrew", "andrew-plan")
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir, mcpUnclaimedAfter = 1.seconds))
        try {
            val departed = withTimeout(TIMEOUT) { host.state.first { s -> s.departures().any { it.identity == before } } }
                .departures().single { it.identity == before }
            val kept = KeptSessionFile(dir.resolve(KeptSessionFile.NAME))
            withTimeout(TIMEOUT) { while (kept.load().isNotEmpty()) delay(50) }
            assertAll(
                { assertEquals(DepartReason.Quiet, departed.reason, "the host judged it quiet") },
                { assertTrue(kept.load().isEmpty(), "and keeps it no more") },
            )
        } finally {
            host.close()
        }
    }

    /** Starts a host, opens [surface] as a Claude at [query], stops the host, and returns who opened it. */
    private suspend fun openThenRestart(query: String, surface: String): PeerIdentity {
        val host = startHost()
        return try {
            drawAndLearnWho(connect(url(host, query)), host, surface)
        } finally {
            host.close()
        }
    }

    private suspend fun startHost(port: Int = 0) = BeeHost.start(scope, HostConfig(port = port, dataDir = dir))

    private fun url(host: BeeHost, query: String) = "http://127.0.0.1:${host.port}${BeeHost.MCP_PATH}$query"

    private suspend fun connect(url: String): Client =
        Client(Implementation("test", "0")).apply { connect(StreamableHttpClientTransport(http, url)) }

    private suspend fun drawAndLearnWho(claude: Client, host: BeeHost, surface: String): PeerIdentity {
        val opened = claude.callTool(BeeTools.OPEN, openArgs(surface))
        check(opened.isError != true) { "rig: the open failed: ${opened.content.filterIsInstance<TextContent>().map { it.text }}" }
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
        const val ANOTHER_MACHINE = "192.168.1.20"
    }
}
