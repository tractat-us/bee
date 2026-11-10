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
        val before = openThenRestart("?name=alex", "alex-plan")
        val host = startHost()
        try {
            val claude = connect(url(host, "?name=alex"))
            val after = drawAndLearnWho(claude, host, "alex-after")
            val state = host.state.value
            assertAll(
                { assertEquals(before, after, "the same identity, so the surfaces it opened are still its own") },
                { assertTrue(state.departures().none { it.identity == before }, "never departed, so its cards aren't muted: ${state.departures()}") },
                { assertEquals(before, state.meta("alex-plan")?.openedBy, "rig: the old surface survived the restart") },
            )
            claude.close()
        } finally {
            host.close()
        }
    }

    /** Another key at the same name is someone else: they never get alex's identity. */
    @Test
    fun anotherKeyWithTheSameNameGetsAnIdentityOfItsOwn() = runBlocking {
        val before = openThenRestart("?name=alex", "alex-plan")
        val host = startHost()
        try {
            // On loopback the host admits any key, so the key here only names whose Claude it is.
            val stranger = connect(url(host, "?name=alex&k=someone-else"))
            val strangers = drawAndLearnWho(stranger, host, "stranger-surface")
            val alex = connect(url(host, "?name=alex"))
            val alexs = drawAndLearnWho(alex, host, "alex-after")
            assertAll(
                { assertNotEquals(before, strangers, "another key doesn't inherit alex's surfaces") },
                { assertEquals(before, alexs, "control: alex's own URL still does") },
            )
            stranger.close()
            alex.close()
        } finally {
            host.close()
        }
    }

    /** One identity, one session: a second session at the same URL never takes the first's surfaces. */
    @Test
    fun aSecondSessionAtTheSameUrlNeverTakesTheFirstsIdentity() = runBlocking {
        val before = openThenRestart("?name=alex", "alex-plan")
        val host = startHost()
        try {
            val first = connect(url(host, "?name=alex"))
            val firsts = drawAndLearnWho(first, host, "first-after")
            val second = connect(url(host, "?name=alex"))
            val seconds = drawAndLearnWho(second, host, "second-after")
            assertAll(
                { assertEquals(before, firsts, "rig: the first session back comes back as alex") },
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
     * first-contact push brings the old host's presence slot for alex back, old claims node and
     * all. The voter drops that node a learner grace after boot; alex, live on a new node, must
     * not be departed for it.
     */
    @Test
    fun aPeerThatSurvivesTheRestartNeverGetsTheResumedIdentityDeparted() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val first = startHost(port)
        val presenter = BridgePeer.join(HostAddress.parse("ws://127.0.0.1:$port"), "presenter", peerHttpClient(), scope, Random(9), { Clock.System.now() })
        presenter.start()
        val before = drawAndLearnWho(connect(url(first, "?name=alex")), first, "alex-plan")
        withTimeout(TIMEOUT) { presenter.board.state.first { s -> s.latestPresence().any { it.identity == before && it.claimsNode != null } } }
        first.close()
        val host = startHost(port)
        try {
            val claude = connect(url(host, "?name=alex"))
            val after = drawAndLearnWho(claude, host, "alex-after")
            // The rig: both slots are on the new board, the pushed-back one and the resumed one.
            withTimeout(TIMEOUT) { host.state.first { s -> s.latestPresence().count { it.identity == before } >= 2 } }
            val departed = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE * 2 + 10.seconds) {
                host.state.first { s -> s.departures().any { it.identity == before } }
            }
            assertAll(
                { assertEquals(before, after, "rig: alex came back as itself") },
                { assertEquals(null, departed?.departures()?.filter { it.identity == before }, "alex, live, was never departed") },
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
        val ticket = assertIs<Opened.Ticket>(first.pairing.open(Caller.byPeer(ANOTHER_MACHINE)))
        val device = assertIs<Attempt.Admitted>(first.pairing.attempt(ticket.id, PeerIdentity.human("alex"), ticket.code)).key.value
        val join = first.pairing.joinKey.value
        val (onDevice, onJoin) = try {
            drawAndLearnWho(connect(url(first, "?name=alex&k=$device")), first, "device-plan") to
                drawAndLearnWho(connect(url(first, "?name=bea&k=$join")), first, "join-plan")
        } finally {
            first.close()
        }
        val host = startHost()
        try {
            val onDeviceAgain = drawAndLearnWho(connect(url(host, "?name=alex&k=$device")), host, "device-after")
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
     * A session that can't come back as itself isn't kept: a host that stops departs it `Left`, as
     * before bee#126, so its cards read as left at once, not as Away for twenty minutes.
     */
    @Test
    fun aSessionThatCannotComeBackDepartsLeftWhenTheHostStops() = runBlocking {
        val first = startHost()
        val join = first.pairing.joinKey.value
        val (bea, alex) = try {
            drawAndLearnWho(connect(url(first, "?name=bea&k=$join")), first, "bea-plan") to
                drawAndLearnWho(connect(url(first, "?name=alex")), first, "alex-plan")
        } finally {
            first.close()
        }
        val kept = KeptSessionFile(dir.resolve(KeptSessionFile.NAME)).load().map { it.session }
        val host = startHost()
        try {
            val departed = withTimeout(TIMEOUT) { host.state.first { s -> s.departures().any { it.identity == bea } } }.departures()
            assertAll(
                { assertEquals(DepartReason.Left, departed.single { it.identity == bea }.reason, "the join link's session left with its host") },
                { assertTrue(departed.none { it.identity == alex }, "control: alex, who can come back, didn't") },
                { assertEquals(listOf(alex), kept.map { it.current }, "only alex is kept") },
            )
        } finally {
            host.close()
        }
    }

    /**
     * `--forget-devices` revokes every device's key, so a session kept under one can never come
     * back: the host retires it at its first check, not after the whole window. A session kept with
     * no key, on the host's own machine, still waits for its client.
     */
    @Test
    fun forgettingDevicesRetiresTheSessionsKeptUnderTheirKeys() = runBlocking {
        val first = startHost()
        val ticket = assertIs<Opened.Ticket>(first.pairing.open(Caller.byPeer(ANOTHER_MACHINE)))
        val device = assertIs<Attempt.Admitted>(first.pairing.attempt(ticket.id, PeerIdentity.human("alex"), ticket.code)).key.value
        val (alex, iain) = try {
            drawAndLearnWho(connect(url(first, "?name=alex&k=$device")), first, "alex-plan") to
                drawAndLearnWho(connect(url(first, "?name=iain")), first, "iain-plan")
        } finally {
            first.close()
        }
        val host = BeeHost.start(scope, HostConfig(port = 0, dataDir = dir, keyReset = KeyReset.ForgetDevices))
        try {
            val departed = withTimeout(Departures.CHECK_EVERY + TIMEOUT) { host.state.first { s -> s.departures().any { it.identity == alex } } }
                .departures()
            val kept = KeptSessionFile(dir.resolve(KeptSessionFile.NAME))
            withTimeout(TIMEOUT) { while (kept.load().any { it.session.current == alex }) delay(50) }
            assertAll(
                { assertEquals(DepartReason.Quiet, departed.single { it.identity == alex }.reason, "its key is gone, so its client can't come back") },
                { assertTrue(departed.none { it.identity == iain }, "control: the keyless session on this machine still waits") },
                { assertEquals(listOf(iain), kept.load().map { it.session.current }, "only iain is still kept") },
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
        val before = openThenRestart("?name=alex", "alex-plan")
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

    /**
     * As above, but Claude Code is slow to come back: it re-initializes only once the voter has
     * dropped the old claims node, as it does after a `/mcp` or a relaunch. Until then the pushed-back
     * slot is alex's only entry; kept for its client, alex is still never departed.
     */
    @Test
    fun aClientThatComesBackAfterTheLearnerGraceIsStillItself() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val first = startHost(port)
        val presenter = BridgePeer.join(HostAddress.parse("ws://127.0.0.1:$port"), "presenter", peerHttpClient(), scope, Random(11), { Clock.System.now() })
        presenter.start()
        val before = drawAndLearnWho(connect(url(first, "?name=alex")), first, "alex-plan")
        withTimeout(TIMEOUT) { presenter.board.state.first { s -> s.latestPresence().any { it.identity == before && it.claimsNode != null } } }
        first.close()
        val host = startHost(port)
        try {
            // The rig: the presenter pushed alex's old slot back, and nothing else speaks for alex.
            withTimeout(TIMEOUT) { host.state.first { s -> s.latestPresence().any { it.identity == before } } }
            delay(ClaimsVoter.LEARNER_GRACE + 5.seconds)
            val whileAway = host.state.value.departures().filter { it.identity == before }
            val claude = connect(url(host, "?name=alex"))
            val after = drawAndLearnWho(claude, host, "alex-after")
            val departed = withTimeoutOrNull(ClaimsVoter.LEARNER_GRACE + 10.seconds) {
                host.state.first { s -> s.departures().any { it.identity == before } }
            }
            assertAll(
                { assertEquals(emptyList(), whileAway, "not departed while its client was away") },
                { assertEquals(before, after, "alex came back as itself, late") },
                { assertEquals(null, departed?.departures()?.filter { it.identity == before }, "and was never departed") },
            )
            claude.close()
        } finally {
            presenter.board.close()
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
        Client(Implementation("claude-code", "2.1.319")).apply { connect(StreamableHttpClientTransport(http, url)) }

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
