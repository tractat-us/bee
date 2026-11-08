package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.io.PrintStream
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Lazy, so the logger (whose start-up prints a line to stdout) starts only after main() has
// pointed System.out at stderr. A plain top-level val starts with MainKt, before main runs.
private val logger by lazy { KotlinLogging.logger {} }

/** The host the bridge joins when `$BEE_HOST` is unset: the default port on loopback (plan, "Decisions"). */
private const val DEFAULT_HOST = "ws://127.0.0.1:8233"

/**
 * The bridge: a stdio MCP server, one per Claude session (spec §5.2), joined to the board.
 *
 * It holds a replica of the board (`/ws/board`) and a claims learner (`/ws/claims`) on the host at
 * `$BEE_HOST` (`ws://host:port`, default [DEFAULT_HOST]). Retiring an identity, on `/clear` or on
 * finding itself retired, proposes its `Depart` through Raft.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    // stdout carries the MCP protocol, and nothing else may write there. Keep the real stream for
    // the transport and point System.out at stderr, so a log line or a stray print can't corrupt it.
    val protocolOut: PrintStream = System.out
    System.setOut(System.err)

    val host = System.getenv("BEE_HOST")?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_HOST
    val random = SecureRandom().asKotlinRandom()
    val clock = { Clock.System.now() }
    // The bridge's own work (the board, claims, a retire on /clear) runs here; one failure must not end the rest.
    val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = HttpClient(CIO) { install(WebSockets) }
    val author = ReplicaId("mcp-${Uuid.random()}")
    val board = BoardClient(
        author,
        SpokeLoom(http, Dispatchers.Default, bridgeScope, author.value, random, clock),
        Rendezvous.Existing(WebSocketAdvertisement("$host/ws/board", PeerId("bee-host"), "board")),
        bridgeScope,
        clock,
    )
    // TODO(kuilt#3110): one refused dial ends clusterClient's reconnect loop for good, so the
    // connection rebuilds a failed link, under a fresh node each time (kuilt keeps a NodeId unique).
    val claims = ClaimsConnection(
        scope = bridgeScope,
        nextNode = { NodeId("mcp-${Uuid.random()}") },
        open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, host, linkScope, clock, random) },
        onFailure = { failure -> logger.warn(failure) { "The claims link failed; rebuilding it." } },
        retryDelay = ClaimsConnection.jitteredBackoff(random),
    )
    val identity = BridgeIdentity(sessionName(), random) { old, reason ->
        val committed = claims.depart(old, reason)
        logger.info { "Retired $old ($reason); its Depart ${if (committed) "committed" else "is pending until claims are back"}." }
    }
    val presence = Presence(board, author, claims.link.filterNotNull().map { it.node }, clock, bridgeScope) { failure -> logger.warn(failure) { "Presence tracking failed." } }
    val bridge = BeeBridge(board, author, claims, identity, presence, clock)
    board.connect()
    runBlocking { bridge.announce() }
    logger.info { "bee-mcp ${ProcessHandle.current().pid()} starts as ${identity.current.value}, on $host" }

    ClearControl(identity, bridgeScope).use { clear ->
        // Claude Code ends its MCP servers with a signal, not only by closing stdin (spike 3), so
        // the run file goes on shutdown too. A SIGKILL still leaves it; the hook then finds a dead port.
        Runtime.getRuntime().addShutdownHook(Thread(clear::close, "bee-clear-control-close"))
        clear.start()
        val server = Server(
            Implementation(name = "bee", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        WaitTool.register(server, BoardActionSource(bridge))
        BeeTools.register(server, bridge)
        runBlocking {
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
            val closed = CompletableDeferred<Unit>()
            val session = server.createSession(transport)
            session.onClose { closed.complete(Unit) }
            closed.await()
            board.close()
        }
    }
    bridgeScope.cancel()
    http.close()
    logger.info { "bee-mcp ends." }
}

/** `$BEE_SESSION_NAME`, else the working directory's basename (spec §5.2). */
private fun sessionName(): String =
    System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() }
        ?: Path.of("").toAbsolutePath().fileName?.toString()
        ?: "bee"
