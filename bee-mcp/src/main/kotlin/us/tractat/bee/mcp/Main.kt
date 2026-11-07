package us.tractat.bee.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
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

    ClearControl(identity).use { clear ->
        // Claude Code ends its MCP servers with a signal, not only by closing stdin (spike 3), so
        // the run file goes on shutdown too. A SIGKILL still leaves it; the hook then finds a dead port.
        Runtime.getRuntime().addShutdownHook(Thread(clear::close, "bee-clear-control-close"))
        clear.start()
        val server = Server(
            Implementation(name = "bee", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        WaitTool.register(server, NoActions)
        addHoldForSpike3(server)
        runBlocking {
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
            val closed = CompletableDeferred<Unit>()
            val session = server.createSession(transport)
            session.onClose { closed.complete(Unit) }
            closed.await()
        }
    }
    logger.info { "bee-mcp ends." }
}

// TEMPORARY (spike 3 Step 3): a diagnostic hold, removed before the PR.
private fun addHoldForSpike3(server: Server) {
    server.addTool(
        name = "bee_hold",
        description = "Diagnostic: holds for `seconds`, sending progress every 10 s when `progress` is true.",
        inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(
            properties = kotlinx.serialization.json.buildJsonObject {
                put("seconds", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("integer")) })
                put("progress", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("boolean")) })
            },
            required = listOf("seconds"),
        ),
    ) { request ->
        val seconds = request.arguments!!["seconds"].toString().trim('"').toLong()
        val progress = request.arguments?.get("progress")?.toString()?.trim('"') != "false"
        val token = request.params.meta?.progressToken
        val start = System.nanoTime()
        logger.info { "bee_hold start seconds=$seconds progress=$progress token=$token meta=${request.params.meta?.json}" }
        try {
            var held = 0L
            while (held < seconds) {
                val step = minOf(10L, seconds - held)
                kotlinx.coroutines.delay(step * 1000)
                held += step
                if (progress && token != null) {
                    notification(
                        io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification(
                            io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams(token, held.toDouble(), seconds.toDouble(), "holding"),
                        ),
                    )
                }
                logger.info { "bee_hold held=$held" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.info { "bee_hold cancelled after ${(System.nanoTime() - start) / 1_000_000} ms" }
            throw e
        }
        logger.info { "bee_hold returns after ${(System.nanoTime() - start) / 1_000_000} ms" }
        io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(
            content = listOf(io.modelcontextprotocol.kotlin.sdk.types.TextContent("held $seconds s")),
        )
    }
}

/** `$BEE_SESSION_NAME`, else the working directory's basename (spec §5.2). */
private fun sessionName(): String =
    System.getenv("BEE_SESSION_NAME")?.takeIf { it.isNotBlank() }
        ?: Path.of("").toAbsolutePath().fileName?.toString()
        ?: "bee"
