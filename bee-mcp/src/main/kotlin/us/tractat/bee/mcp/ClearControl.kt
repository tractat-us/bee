package us.tractat.bee.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import us.tractat.bee.core.DepartReason
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * The `/clear` channel (A8): a loopback `POST /retire` that retires [identity] with
 * [DepartReason.Cleared], and the run file `<runDir>/<claudePid>.json` (`{"port":N}`) through
 * which `bin/bee-session-start-hook` finds it.
 *
 * With no `claude` ancestor ([claudePid] `null`) the endpoint still serves, but no run file is
 * written, so no hook can reach it: a bridge run by hand has no `/clear` to hear.
 */
class ClearControl(
    private val identity: BridgeIdentity,
    private val runDir: Path = defaultRunDir(),
    private val claudePid: Long? = ClaudeAncestry.claudePid(),
) : AutoCloseable {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(RETIRE_PATH, ::handleRetire)
        }

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

    private val closed = AtomicBoolean(false)

    /**
     * Stops serving and deletes the run file, unless a later bridge has since claimed it.
     * Idempotent, because both the bridge's normal exit and its shutdown hook call it.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        runFile?.takeIf { it.exists() && portIn(it) == port }?.deleteIfExists()
    }

    private fun handleRetire(exchange: HttpExchange) {
        exchange.use {
            // A context matches by prefix; only the exact path retires.
            if (it.requestURI.path != RETIRE_PATH) return it.respond(404, """{"error":"not found"}""")
            if (it.requestMethod != "POST") return it.respond(405, """{"error":"POST only"}""")
            val retired = identity.current.value
            try {
                runBlocking { identity.retire(DepartReason.Cleared) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Retiring $retired on /clear failed." }
                return it.respond(500, buildJsonObject { put("error", e.message ?: e.toString()) }.toString())
            }
            logger.info { "/clear retired $retired; now ${identity.current.value}" }
            it.respond(
                200,
                buildJsonObject {
                    put("retired", retired.toString())
                    put("current", identity.current.value.toString())
                }.toString(),
            )
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    /** Writes then renames, so the hook never reads a half-written file. */
    private fun writeRunFile(file: Path) {
        Files.createDirectories(runDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val tmp = Files.createTempFile(runDir, "${file.fileName}.", ".tmp")
        tmp.writeText(buildJsonObject { put("port", port) }.toString())
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun portIn(file: Path): Int? =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject["port"]?.jsonPrimitive?.int }.getOrNull()

    companion object {
        const val RETIRE_PATH = "/retire"

        /** `$BEE_RUN_DIR`, else `~/.bee/run`. The hook reads the same variable. */
        fun defaultRunDir(): Path =
            System.getenv("BEE_RUN_DIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".bee", "run")
    }
}
