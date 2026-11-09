package us.tractat.bee.telemetry

import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.FileChannelDurableStore
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * One process's telemetry, open: [telemetry] to record into, kept in a directory this process
 * alone holds, and every logback line captured beside it. Invariant: **opening never fails the
 * process.** A switched-off, busy or broken directory opens as [BeeTelemetry.Off], saying why
 * once in the log.
 *
 * The directory holds `buffer/` (kuilt-otel's [WarpTelemetry], bounded by [MAX_LOG_RECORDS] and
 * [MAX_SPANS]), `replica` (the buffer's CRDT author, kept across restarts) and `.lock`.
 */
class ProcessTelemetry private constructor(
    val telemetry: BeeTelemetry,
    /** Where to stamp what the process learns late, such as its board. Nothing reads it when [telemetry] is off. */
    val resource: TelemetryResource,
    /** The directory this process records into, or null when [telemetry] is off. */
    val dir: Path?,
    private val stop: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /**
     * Detaches the log edge, gives the queue [CLOSE_GRACE] to reach the buffer, and lets the
     * directory go. Only the first call does anything, so a shutdown hook and a normal exit may both call it.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) stop()
    }

    companion object {
        /** The kill switch: `BEE_TELEMETRY=off` (or `0`, `false`, `no`) opens nothing. */
        const val SWITCH: String = "BEE_TELEMETRY"

        const val MAX_LOG_RECORDS: Int = 10_000

        /** Fewer than log records: kuilt rewrites the whole span buffer on each export. */
        const val MAX_SPANS: Int = 1_000

        private const val REPLICA_BYTES = 16
        private val CLOSE_GRACE = 2.seconds
        private val log = KotlinLogging.logger("${RecordingTelemetry.LOGGER_PREFIX}.ProcessTelemetry")

        /** Whether [env]'s [SWITCH] turns telemetry off. */
        fun switchedOff(env: (String) -> String?): Boolean = env(SWITCH)?.trim()?.lowercase() in setOf("off", "0", "false", "no")

        /**
         * Opens telemetry in the first of [dirs] no other process holds, stamping [resource] on
         * every record. Off when [env] switches it off, when every one of [dirs] is held, or when
         * the directory won't open. [store] opens the buffer in a directory: a test passes one
         * that fails. [captureLogs] attaches the logback edge.
         */
        @Suppress("LongParameterList")
        fun open(
            dirs: List<Path>,
            resource: Map<String, String>,
            env: (String) -> String? = System::getenv,
            clock: Clock = Clock.System,
            random: Random = SecureRandom().asKotlinRandom(),
            store: (Path) -> DurableStore = { FileChannelDurableStore(Files.createDirectories(it).toFile()) },
            captureLogs: Boolean = true,
        ): ProcessTelemetry {
            if (switchedOff(env)) return off()
            return try {
                val (dir, lock) = dirs.firstNotNullOfOrNull { dir -> lockOrNull(dir)?.let { dir to it } }
                    ?: return off().also { log.warn { "Telemetry is off: other processes hold every directory it could use (${dirs.joinToString()})." } }
                try {
                    start(dir, lock, TelemetryResource(resource), clock, random, store, captureLogs)
                } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                    lock.release()
                    lock.channel().close()
                    throw failure
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                log.warn(failure) { "Telemetry is off: its directory wouldn't open." }
                off()
            }
        }

        @Suppress("LongParameterList")
        private fun start(
            dir: Path,
            lock: FileLock,
            resource: TelemetryResource,
            clock: Clock,
            random: Random,
            store: (Path) -> DurableStore,
            captureLogs: Boolean,
        ): ProcessTelemetry {
            val warp = WarpTelemetry(replicaOf(dir, random), store(dir.resolve("buffer")), maxSpans = MAX_SPANS, maxLogRecords = MAX_LOG_RECORDS)
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName("bee-telemetry"))
            val recording = RecordingTelemetry(warp, resource, clock, random, scope)
            val edge: Pair<LoggerContext, LogbackEdge>? = if (captureLogs) LogbackEdge.install(recording) else null
            return ProcessTelemetry(recording, resource, dir) {
                edge?.let { (context, appender) -> appender.uninstall(context) }
                runBlocking { recording.close(CLOSE_GRACE) }
                scope.cancel()
                lock.release()
                lock.channel().close()
            }
        }

        /** Telemetry that records nothing and holds nothing open. */
        fun off(): ProcessTelemetry = of(BeeTelemetry.Off)

        /** [telemetry], held in no directory and closed by nothing: a test's own recorder. */
        fun of(telemetry: BeeTelemetry): ProcessTelemetry = ProcessTelemetry(telemetry, TelemetryResource(emptyMap()), null) {}

        /** A hold on [dir], or null if another process (or this one) holds it. */
        private fun lockOrNull(dir: Path): FileLock? {
            Files.createDirectories(dir)
            val channel = FileChannel.open(dir.resolve(".lock"), CREATE, WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (failure: IOException) {
                channel.close()
                throw failure
            }
            if (lock == null) channel.close()
            return lock
        }

        /** The buffer's CRDT author: the one [dir] keeps, or a new one it keeps from now on. */
        private fun replicaOf(dir: Path, random: Random): ReplicaId {
            val file = dir.resolve("replica")
            val kept = if (Files.exists(file)) Files.readString(file).trim() else ""
            return ReplicaId(kept.ifEmpty { "bee-${random.nextBytes(REPLICA_BYTES).toHexString()}".also { Files.writeString(file, it) } })
        }
    }
}
