package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.HostAddress
import java.io.PrintStream
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val http = peerHttpClient()
    val peer = BridgePeer.join(host, sessionName(), http, bridgeScope, random, clock)
    val bridge = peer.bridge
    val board = peer.board
    val pin = peer.pin
    val identity = peer.identity
    peer.start()
    val anotherBoard = CompletableDeferred<AnotherBoard>()
    endOnAnotherBoard(board, pin, http, host, bridgeScope, anotherBoard)
    runBlocking { bridge.announce() }
    logger.info { "bee-mcp ${ProcessHandle.current().pid()} starts as ${identity.current.value}, on $host" }

    // Once, by whichever way out comes first; a second caller waits for the first to finish.
    val leaving = lazy { leave(bridge, board) }
    ClearControl(identity, bridgeScope).use { clear ->
        // Claude Code ends its MCP servers with a signal, not only by closing stdin (spike 3), so
        // the run file goes on shutdown too. A SIGKILL still leaves it; the hook then finds a dead port.
        Runtime.getRuntime().addShutdownHook(Thread(clear::close, "bee-clear-control-close"))
        // Either way out, stdin closing or that signal, the session left on purpose: say so first.
        Runtime.getRuntime().addShutdownHook(Thread({ leaving.value }, "bee-mcp-leave"))
        clear.start()
        val server = BeeServer.create(bridge)
        runBlocking {
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
            val closed = CompletableDeferred<Unit>()
            val session = server.createSession(transport)
            session.onClose { closed.complete(Unit) }
            anotherBoard.invokeOnCompletion { closed.complete(Unit) }
            closed.await()
        }
    }
    leaving.value
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

/**
 * How long an ending bridge waits for its `Depart(Left)` to commit, and for the host to acknowledge
 * its writes: both at once ([endSession]). A single voter commits inline and the host acknowledges
 * within milliseconds, so this only bounds a host that's gone, or a link that's half-open.
 *
 * Measured (e2e `departures.spec.mjs`, host on loopback): from SIGINT to the bridge's exit, `Left`
 * committed, took about 330 ms. Not measured: Claude Code's own grace. Public reports say `/exit`
 * sends SIGINT, SIGTERM about 100 ms later, then SIGKILL about 400 ms after that; some say SIGKILL
 * at once. So this bound is longer than that grace, and a SIGKILL before the commit leaves the
 * host to depart the identity `Crashed`.
 */
private val LEAVE_BOUND = 1.seconds

/**
 * Ends [bridge]'s session on the board, by either way out: stdin closing, or the shutdown hook a
 * signal runs, which is how Claude Code ends its servers. Both flush [board]'s writes and depart
 * the identity as [us.tractat.bee.core.DepartReason.Left], together within [LEAVE_BOUND]
 * ([endSession]). A `Depart` that doesn't commit is departed `Crashed` by the host instead.
 */
private fun leave(bridge: BeeBridge, board: BoardClient) {
    runBlocking { endSession(board, LEAVE_BOUND) { within -> depart(bridge, within) } }
}

/** Departs [bridge]'s identity as `Left`, waiting at most [within], and says how that went. */
private suspend fun depart(bridge: BeeBridge, within: Duration) {
    val who = bridge.self
    val committed = try {
        bridge.leave(within)
    } catch (failure: Exception) {
        // The top of a way out: a cancellation reaching here is a failure like any other.
        logger.warn(failure) { "Leaving the board as $who failed; the host will depart it as crashed." }
        return
    }
    if (committed) {
        logger.info { "Left the board as $who." }
    } else {
        logger.warn { "$who's Depart(Left) didn't commit within $within; the host will depart it as crashed." }
    }
}

/**
 * An ending bridge's last steps on the board, run at once so the exit takes at most [within], not
 * twice it: [depart], and wait for the host to acknowledge [board]'s writes, then close [board]
 * (bee#116). Closing parts the connection, which cancels any write the seam hasn't sent yet
 * (kuilt's `Quilter.close`); just after a redial, the first-contact sync can hold one back for
 * longer than a session takes to end. An offline write is lost either way: it would ride the next
 * connection's FullState.
 *
 * @param depart bounds itself by the [within] it's given, and doesn't throw
 * @return whether the host acknowledged the writes in time
 */
internal suspend fun endSession(board: BoardClient, within: Duration, depart: suspend (Duration) -> Unit): Boolean = try {
    coroutineScope {
        launch { depart(within) }
        flushed(board, within)
    }
} finally {
    withContext(NonCancellable) { board.close() }
}

/** Whether the host acknowledged [board]'s writes within [within]; it says why not, if not. */
private suspend fun flushed(board: BoardClient, within: Duration): Boolean {
    val acknowledged = withTimeoutOrNull(within) { board.awaitAcknowledged() }
    when (acknowledged) {
        true -> Unit
        false -> logger.warn { "This bridge isn't connected to the host, so any writes it made since its last connection are lost." }
        null -> logger.warn { "The host didn't acknowledge this bridge's last writes within $within; any not yet sent are lost." }
    }
    return acknowledged == true
}

/** The exit status of a bridge that ended because its host keeps another board. */
private const val EXIT_ANOTHER_BOARD = 2

/** `$BEE_SESSION_NAME`, else the working directory's basename (spec §5.2). */
private fun sessionName(): String =
    System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() }
        ?: Path.of("").toAbsolutePath().fileName?.toString()
        ?: "bee"
