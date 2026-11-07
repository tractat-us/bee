package us.tractat.bee.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import us.tractat.bee.core.DepartReason
import us.tractat.bee.core.PeerIdentity
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The `/clear` channel (A8): a loopback `POST /retire` that retires [identity] with
 * [DepartReason.Cleared], and the run file `<runDir>/<claudePid>.json` through which
 * `bin/bee-session-start-hook` finds it.
 *
 * The run file is `{"port":N,"token":"…","pid":<bridge pid>}`, mode 0600, in a 0700 directory.
 * Loopback alone admits other OS users and any web page that can guess the port, so a request must
 * carry `Authorization: Bearer <token>`. A browser can't send that header cross-origin without a
 * preflight, and the server never answers one. The hook checks `pid` is alive before it posts, so a
 * stale file never retires another session's bridge.
 *
 * The retire runs on [scope], a `SupervisorJob` scope the bridge owns, never on the server's
 * threads. The response waits at most [answerWithin]: `200` once it's done, `202` while it's still
 * running (a slow Depart in Task 7), `500` if it failed. Shutdown never waits on a retire.
 *
 * With no `claude` ancestor ([claudePid] `null`) the endpoint still serves, but no run file is
 * written, so no hook can reach it: a bridge run by hand has no `/clear` to hear.
 */
class ClearControl(
    private val identity: BridgeIdentity,
    private val scope: CoroutineScope,
    private val runDir: Path = defaultRunDir(),
    private val claudePid: Long? = ClaudeAncestry.claudePid(),
    private val answerWithin: Duration = 5.seconds,
) : AutoCloseable {
    private val token: String = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
    private val handlers: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(RETIRE_PATH, ::handle)
            executor = handlers
        }
    private val closed = AtomicBoolean(false)

    /** The loopback port `POST /retire` listens on. */
    val port: Int get() = server.address.port

    /** The run file this bridge writes, or `null` when it has no `claude` ancestor. */
    val runFile: Path? = claudePid?.let { runDir.resolve("$it.json") }

    /** Starts serving and writes the run file. */
    fun start() {
        server.start()
        runFile?.let(::writeRunFile) ?: logger.warn { "No claude ancestor: /clear cannot reach this bridge." }
        logger.info { "ClearControl on 127.0.0.1:$port, run file $runFile" }
    }

    /**
     * Stops serving and deletes the run file, unless a later bridge has since claimed it.
     * Idempotent, because both the bridge's normal exit and its shutdown hook call it.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        handlers.shutdown()
        runFile?.takeIf { it.exists() && tokenIn(it) == token }?.deleteIfExists()
    }

    private fun handle(exchange: HttpExchange) = exchange.use {
        when {
            // A context matches by prefix; only the exact path retires.
            it.requestURI.path != RETIRE_PATH -> it.respond(404, error("not found"))
            !authorized(it) -> it.respond(401, error("missing or wrong bearer token"))
            it.requestMethod != "POST" -> it.respond(405, error("POST only"))
            else -> retire(it)
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val presented = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return MessageDigest.isEqual(presented.encodeToByteArray(), "Bearer $token".encodeToByteArray())
    }

    private fun retire(exchange: HttpExchange) {
        val retiring = scope.async {
            try {
                identity.retire(DepartReason.Cleared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Retiring on /clear failed." }
                throw e
            }
        }
        val outcome: Result<PeerIdentity>? = try {
            runBlocking { withTimeoutOrNull(answerWithin) { retiring.await() } }?.let { Result.success(it) }
        } catch (e: Exception) {
            // This thread is no coroutine: a CancellationException here is the retire's (its scope
            // closed), not ours, so it is an outcome to report, not a cancellation to rethrow.
            Result.failure(e)
        }
        when {
            outcome == null -> exchange.respond(202, buildJsonObject { put("retiring", true) }.toString())
            outcome.isFailure -> exchange.respond(500, error(outcome.exceptionOrNull().toString()))
            else -> {
                val retired = outcome.getOrThrow()
                logger.info { "/clear retired $retired; now ${identity.current.value}" }
                exchange.respond(
                    200,
                    buildJsonObject {
                        put("retired", retired.toString())
                        put("current", identity.current.value.toString())
                    }.toString(),
                )
            }
        }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    /** Owner-only directory and file; writes then renames, so the hook never reads a half-written file. */
    private fun writeRunFile(file: Path) {
        Files.createDirectories(runDir)
        // Enforced even when the directory already existed with looser bits.
        Files.setPosixFilePermissions(runDir, PosixFilePermissions.fromString("rwx------"))
        val tmp = Files.createTempFile(
            runDir,
            "${file.fileName}.",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        tmp.writeText(
            buildJsonObject {
                put("port", port)
                put("token", token)
                put("pid", ProcessHandle.current().pid())
            }.toString(),
        )
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun tokenIn(file: Path): String? =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject["token"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()

    companion object {
        const val RETIRE_PATH = "/retire"
        private const val TOKEN_BYTES = 32

        /** `$BEE_RUN_DIR`, else `~/.bee/run`. The hook reads the same variable. */
        fun defaultRunDir(): Path =
            System.getenv("BEE_RUN_DIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".bee", "run")
    }
}
