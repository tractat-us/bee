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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.askBoardId
import us.tractat.bee.core.pinnedTo
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.io.PrintStream
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Lazy, so the logger (whose start-up prints a line to stdout) starts only after main() has
// pointed System.out at stderr. A plain top-level val starts with MainKt, before main runs.
private val logger by lazy { KotlinLogging.logger {} }

/**
 * The bridge: a stdio MCP server, one per Claude session (spec §5.2), joined to the board.
 *
 * It holds a replica of the board (`/ws/board`) and a claims learner (`/ws/claims`) on the host at
 * `$BEE_HOST` ([beeHostFrom]: `ws://host:port`, or a `--lan` host's join link; default
 * [DEFAULT_HOST]). A join link's key goes on both sockets: it admits a bridge on another machine.
 * Retiring an identity, on `/clear` or on finding itself retired, proposes its `Depart` through Raft.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    // stdout carries the MCP protocol, and nothing else may write there. Keep the real stream for
    // the transport and point System.out at stderr, so a log line or a stray print can't corrupt it.
    val protocolOut: PrintStream = System.out
    System.setOut(System.err)

    // Before anything starts: a BEE_HOST that names no host ends the bridge here, saying why.
    val host = beeHostFrom(System.getenv("BEE_HOST"))
    val random = SecureRandom().asKotlinRandom()
    val clock = { Clock.System.now() }
    // The bridge's own work (the board, claims, a retire on /clear) runs here; one failure must not end the rest.
    val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = HttpClient(CIO) { install(WebSockets) }
    val author = ReplicaId("mcp-${Uuid.random()}")
    // The bridge's replica starts empty, so it belongs to the first board the host names; both
    // its sockets name that board from then on, and a wiped host refuses them (bee#46).
    val pin = BoardPin.learnedFrom { http.askBoardId(host) }
    val board = BoardClient(
        author,
        SpokeLoom(http, Dispatchers.Default, bridgeScope, author.value, random, clock).pinnedTo(pin),
        Rendezvous.Existing(WebSocketAdvertisement(host.socket("/ws/board"), PeerId("bee-host"), "board")),
        bridgeScope,
        clock,
    )
    // TODO(kuilt#3110): one refused dial ends clusterClient's reconnect loop for good, so the
    // connection rebuilds a failed link, under a fresh node each time (kuilt keeps a NodeId unique).
    val claims = ClaimsConnection(
        scope = bridgeScope,
        nextNode = { NodeId("mcp-${Uuid.random()}") },
        open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, host, pin, linkScope, clock, random) },
        // A dial's failure can name the URL it dialled, key and all: log it as the host describes it.
        onFailure = { failure -> logger.warn { "The claims link failed; rebuilding it. ${host.describe(failure)}" } },
        retryDelay = ClaimsConnection.jitteredBackoff(random),
    )
    val identity = BridgeIdentity(sessionName(), random) { old, reason ->
        val committed = claims.depart(old, reason)
        logger.info { "Retired $old ($reason); its Depart ${if (committed) "committed" else "is pending until claims are back"}." }
    }
    val presence = Presence(board, author, claims.link.filterNotNull().map { it.node }, clock, bridgeScope) { failure -> logger.warn(failure) { "Presence tracking failed." } }
    val bridge = BeeBridge(board, author, claims, identity, presence, clock, bridgeScope)
    board.connect()
    val anotherBoard = CompletableDeferred<AnotherBoard>()
    endOnAnotherBoard(board, pin, http, host, bridgeScope, anotherBoard)
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
            anotherBoard.invokeOnCompletion { closed.complete(Unit) }
            closed.await()
            board.close()
        }
    }
    bridgeScope.cancel()
    http.close()
    logger.info { "bee-mcp ends." }
    // Closed up first: the run file went with ClearControl's use-block, the clients just above.
    if (anotherBoard.isCompleted) exitProcess(EXIT_ANOTHER_BOARD)
}

/**
 * A host that keeps another board than [pin]'s was wiped, or had its claims log moved aside, and
 * refuses this bridge for good: its replica holds the old board. After a failed dial, the bridge
 * asks, and when the host names another board it says so on stderr and completes [ended], which
 * ends the process with [EXIT_ANOTHER_BOARD] once it has closed up. It doesn't follow the host: a
 * restart does.
 */
private fun endOnAnotherBoard(board: BoardClient, pin: BoardPin, http: HttpClient, host: HostAddress, scope: CoroutineScope, ended: CompletableDeferred<AnotherBoard>) {
    scope.launch {
        val another = awaitAnotherBoard(board.lastFailure, pin) { http.boardNamedOrNull(host) }
        System.err.println(another.message)
        ended.complete(another)
    }
}

/** The exit status of a bridge that ended because its host keeps another board. */
private const val EXIT_ANOTHER_BOARD = 2

/** `$BEE_SESSION_NAME`, else the working directory's basename (spec §5.2). */
private fun sessionName(): String =
    System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() }
        ?: Path.of("").toAbsolutePath().fileName?.toString()
        ?: "bee"
