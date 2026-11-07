package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

// Lazy, so the logger (whose start-up prints a line to stdout) starts only after main() has
// pointed System.out at stderr. A plain top-level val starts with MainKt, before main runs.
private val logger by lazy { KotlinLogging.logger {} }

/**
 * The bridge: a stdio MCP server, one per Claude session (spec §5.2).
 *
 * Until Task 7 joins it to the board, it serves the wait tools over [NoActions] and the `/clear`
 * channel, and retiring an identity only logs.
 */
fun main() {
    // stdout carries the MCP protocol, and nothing else may write there. Keep the real stream for
    // the transport and point System.out at stderr, so a log line or a stray print can't corrupt it.
    val protocolOut: PrintStream = System.out
    System.setOut(System.err)

    val identity = BridgeIdentity(sessionName(), SecureRandom().asKotlinRandom()) { old, reason ->
        // Task 7 turns this into a Depart through Raft.
        logger.info { "Retired $old ($reason)." }
    }
    logger.info { "bee-mcp ${ProcessHandle.current().pid()} starts as ${identity.current.value}" }

    // The bridge's own work (a retire on /clear) runs here; one failure must not end the rest.
    val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ClearControl(identity, bridgeScope).use { clear ->
        // Claude Code ends its MCP servers with a signal, not only by closing stdin (spike 3), so
        // the run file goes on shutdown too. A SIGKILL still leaves it; the hook then finds a dead port.
        Runtime.getRuntime().addShutdownHook(Thread(clear::close, "bee-clear-control-close"))
        clear.start()
        val server = Server(
            Implementation(name = "bee", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        WaitTool.register(server, NoActions)
        runBlocking {
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
            val closed = CompletableDeferred<Unit>()
            val session = server.createSession(transport)
            session.onClose { closed.complete(Unit) }
            closed.await()
        }
    }
    bridgeScope.cancel()
    logger.info { "bee-mcp ends." }
}

/** `$BEE_SESSION_NAME`, else the working directory's basename (spec §5.2). */
private fun sessionName(): String =
    System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() }
        ?: Path.of("").toAbsolutePath().fileName?.toString()
        ?: "bee"
