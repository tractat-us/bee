package us.tractat.bee.companion

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.ClaimsConnection
import us.tractat.bee.core.ClaimsLink
import us.tractat.bee.core.Presence
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.askBoardId
import us.tractat.bee.core.pinnedTo
import us.tractat.bee.mcp.AnotherBoard
import us.tractat.bee.mcp.BeeBridge
import us.tractat.bee.mcp.BoardActionSource
import us.tractat.bee.mcp.BridgeIdentity
import us.tractat.bee.mcp.awaitAnotherBoard
import us.tractat.bee.mcp.boardNamedOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.random.asKotlinRandom
import kotlin.system.exitProcess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Lazy, so the logger starts only after main() has pointed System.out at stderr.
private val logger by lazy { KotlinLogging.logger {} }

/** The host the companion joins when `$BEE_HOST` is unset, as the bridge does. */
private const val DEFAULT_HOST = "ws://127.0.0.1:8233"

private const val USAGE = "bee-companion --screen-dir <dir> --state-dir <dir> --frame-template <frame-template.html>"

/**
 * `bee-companion --screen-dir … --state-dir … --frame-template <plugin>/bin/brainstorm/frame-template.html`:
 * the brainstorming visual companion's directories, kept on the board at `$BEE_HOST` (spec §3).
 *
 * Like the companion server, it writes `state_dir/server-info` on start (with the board's URL),
 * and `server-stopped` when it ends, and prints what it does as JSON lines on stdout. Logs go to
 * stderr.
 */
@OptIn(ExperimentalUuidApi::class)
fun main(args: Array<String>) {
    val out: PrintStream = System.out
    System.setOut(System.err)
    val flags = args.toList().chunked(2).associate { it.first() to it.getOrNull(1) }
    fun dir(flag: String): Path = flags[flag]?.let(Path::of) ?: usage("$flag is required")
    val screenDir = dir("--screen-dir")
    val stateDir = Files.createDirectories(dir("--state-dir"))
    val frameTemplate = dir("--frame-template")
    if (!Files.isDirectory(screenDir)) usage("--screen-dir $screenDir is not a directory")
    if (!Files.isRegularFile(frameTemplate)) usage("--frame-template $frameTemplate is not a file")

    val host = System.getenv("BEE_HOST")?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_HOST
    val random = SecureRandom().asKotlinRandom()
    val clock = { Clock.System.now() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = HttpClient(CIO) { install(WebSockets) }
    val author = ReplicaId("companion-${Uuid.random()}")
    // The companion's replica starts empty, so it belongs to the first board the host names: both
    // its sockets name that board from then on, its state is kept under it, and a wiped host
    // refuses it (bee#46).
    val pin = BoardPin.learnedFrom { http.askBoardId(host) }
    val board = BoardClient(
        author,
        SpokeLoom(http, Dispatchers.Default, scope, author.value, random, clock).pinnedTo(pin),
        Rendezvous.Existing(WebSocketAdvertisement("$host/ws/board", PeerId("bee-host"), "board")),
        scope,
        clock,
    )
    // TODO(kuilt#3110): one refused dial ends clusterClient's reconnect loop, so the connection
    // rebuilds a failed link under a fresh node, as the bridge's does.
    val claims = ClaimsConnection(
        scope = scope,
        nextNode = { NodeId("companion-${Uuid.random()}") },
        open = { node, linkScope -> ClaimsLink.overWebSocket(node, http, host, pin, linkScope, clock, random) },
        onFailure = { failure -> logger.warn(failure) { "The claims link failed; rebuilding it." } },
        retryDelay = ClaimsConnection.jitteredBackoff(random),
    )
    val name = System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() } ?: "companion"
    val identity = BridgeIdentity(name, random) { old, reason -> claims.depart(old, reason) }
    // Presence names the claims link's node, so the host can depart a companion that crashed (bee#28).
    val presence = Presence(board, author, claims.link.filterNotNull().map { it.node }, clock, scope) { failure ->
        logger.warn(failure) { "Presence tracking failed." }
    }
    val bridge = BeeBridge(board, author, claims, identity, presence, clock, scope)
    val print: (JsonObject) -> Unit = { line -> synchronized(out) { out.println(line); out.flush() } }

    val httpHost = host.replaceFirst("ws", "http")
    val pageUrl = URI("$httpHost/").toString()
    val started = buildJsonObject {
        put("type", "server-started")
        put("url", pageUrl)
        put("screen_dir", screenDir.toAbsolutePath().toString())
        put("state_dir", stateDir.toAbsolutePath().toString())
    }
    stateDir.resolve("server-stopped").deleteIfExists()
    stateDir.resolve("server-info").writeText("$started\n")
    // Runs on every exit, a signal's or a failed adapter's: the agent checks server-stopped.
    var stopReason = "stopped"
    Runtime.getRuntime().addShutdownHook(
        Thread({
            stateDir.resolve("server-info").deleteIfExists()
            stateDir.resolve("server-stopped").writeText("${buildJsonObject { put("reason", stopReason) }}\n")
        }, "bee-companion-stopped"),
    )
    print(started)
    logger.info { "bee-companion starts as ${identity.current.value}, on $host" }

    board.connect()
    // The adapter never ends by itself. When it fails, the process ends with it, so it never goes
    // on advertising a companion that handles no screens or clicks. So does a host that keeps
    // another board now: it refuses this companion for good, and a restart joins the new board.
    try {
        runBlocking {
            val boardId = resolveBoard(pin, host)
            logger.info { "The host serves $boardId." }
            launch { throw HostKeepsAnotherBoard(awaitAnotherBoard(board.lastFailure, pin) { http.boardNamedOrNull(host) }) }
            val adapter = CompanionAdapter(
                screenDir, stateDir, frameTemplate, board, author, bridge, BoardActionSource(bridge), clock, print, boardId, random,
            )
            // A graceful stop (SIGTERM) closes the surface. A crash can't, and leaves it open. The
            // brainstorming stop script will send that SIGTERM once the plugin starts bee-companion
            // in the companion server's place; it doesn't reach this process yet.
            // Then it leaves, so the board shows it left, not crashed.
            Runtime.getRuntime().addShutdownHook(Thread({ closeOnStop(adapter); leaveOnStop(bridge) }, "bee-companion-close"))
            bridge.announce()
            adapter.start(this).join()
        }
    } catch (failure: Exception) {
        // main is the top of the stack: a cancellation reaching here is a failure like any other.
        logger.error(failure) { "The companion failed; bee-companion ends." }
        stopReason = "failed: ${failure.message ?: failure::class.simpleName}"
    }
    exitProcess(1)
}

/**
 * How long a stopping companion waits for the host to acknowledge its surface's close: inside the
 * stop script's SIGKILL, which follows its SIGTERM after about 2 s.
 */
private val STOP_BOUND = 1.5.seconds

/** Closes [adapter]'s surface as the process ends, waiting at most [STOP_BOUND] for the host to have it. */
private fun closeOnStop(adapter: CompanionAdapter) {
    val acknowledged = try {
        runBlocking { withTimeoutOrNull(STOP_BOUND) { adapter.closeSurface() } }
    } catch (failure: Exception) {
        // The top of the hook's thread: a cancellation reaching here is a failure like any other.
        logger.warn(failure) { "Closing ${adapter.surface} on stop failed." }
        return
    }
    if (acknowledged != true) logger.warn { "The host didn't acknowledge closing ${adapter.surface} within $STOP_BOUND; it may stay open." }
}

/**
 * How long a stopping companion waits for its `Depart(Left)` to commit, after closing its surface:
 * bee-mcp's bound. Both waits run long only on a host that's gone, when the stop script's SIGKILL
 * may end this one first, and the host departs the companion `Crashed`.
 */
private val LEAVE_BOUND = 1.seconds

/** Departs [bridge]'s identity as left as the process ends, waiting at most [LEAVE_BOUND]. */
private fun leaveOnStop(bridge: BeeBridge) {
    val committed = try {
        runBlocking { bridge.leave(LEAVE_BOUND) }
    } catch (failure: Exception) {
        // The top of the hook's thread: a cancellation reaching here is a failure like any other.
        logger.warn(failure) { "Leaving the board as ${bridge.self} failed; the host will depart it as crashed." }
        return
    }
    if (committed) {
        logger.info { "Left the board as ${bridge.self}." }
    } else {
        logger.warn { "${bridge.self}'s Depart(Left) didn't commit within $LEAVE_BOUND; the host will depart it as crashed." }
    }
}

/**
 * The board [pin] holds, learning it from the host at [host] if it must. A host that isn't up yet
 * is asked again each second; one that answers without naming a board fails.
 *
 * @throws IllegalStateException if the host answers, but names no board
 */
private suspend fun resolveBoard(pin: BoardPin, host: String): BoardId {
    while (true) {
        try {
            return pin.resolve()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreachable: IOException) {
            logger.warn { "The host at $host is unreachable (${unreachable.message}); asking again." }
        }
        delay(1.seconds)
    }
}

/** The host keeps another board than this companion's: `server-stopped` gives this as the reason. */
private class HostKeepsAnotherBoard(another: AnotherBoard) :
    Exception("The host keeps board ${another.named} now, not ${another.pinned}, and refuses this companion: start it again to join ${another.named}.")

private fun usage(problem: String): Nothing {
    System.err.println("$problem\nUsage: $USAGE")
    exitProcess(2)
}
