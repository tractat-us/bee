package us.tractat.bee.telemetry

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.SpanStatus
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.otel.logging.LOGGER_NAME_ATTRIBUTE
import us.tractat.kuilt.store.FileChannelDurableStore
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.copyTo
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** One line of a merged timeline: a log line, an event or a span, from any process. */
data class TimelineEntry(
    /** When it happened (a span: when it started), in nanoseconds since the epoch. */
    val epochNanos: Long,
    val kind: Kind,
    /** A log line's message, an event's name, a span's name. */
    val name: String,
    val attributes: Map<String, String>,
    /** A span's length, else null. */
    val durationNanos: Long? = null,
    /** A log line's severity, else null. */
    val severity: String? = null,
    /** What it is across copies of the same buffer: a record's or span's id. */
    val id: String,
) {
    enum class Kind { LOG, EVENT, SPAN }

    val role: String get() = attributes[Attr.ROLE] ?: "?"
    val peer: String get() = attributes[Attr.PEER] ?: "?"

    companion object {
        fun of(record: LogRecord): TimelineEntry = TimelineEntry(
            epochNanos = record.timestampEpochNanos ?: record.observedEpochNanos ?: 0,
            kind = if (Attr.EVENT in record.attributes) Kind.EVENT else Kind.LOG,
            name = record.body.orEmpty(),
            attributes = record.attributes,
            severity = record.severityText,
            id = record.recordId.toByteArray().toHexString(),
        )

        fun of(span: SpanRecord): TimelineEntry = TimelineEntry(
            epochNanos = span.startEpochNanos,
            kind = Kind.SPAN,
            name = span.name,
            attributes = span.attributes + ((span.status as? SpanStatus.Error)?.let { mapOf("error" to it.message) } ?: emptyMap()),
            durationNanos = span.endEpochNanos - span.startEpochNanos,
            id = span.spanId.toByteArray().toHexString(),
        )
    }
}

/** Which entries a reader wants: by role, by peer (or session) name, and from when. */
data class TimelineFilter(val role: String? = null, val peer: String? = null, val sinceEpochNanos: Long? = null) {
    fun admits(entry: TimelineEntry): Boolean =
        (role == null || entry.role == role) &&
            (peer == null || peer in entry.peer || peer == entry.attributes[Attr.SESSION]) &&
            (sinceEpochNanos == null || entry.epochNanos >= sinceEpochNanos)
}

/**
 * [entries] as one timeline: each record once, however many copies hold it, in time order, as
 * [filter] admits, and only the last [tail] if given. Entries at the same instant keep the order
 * their buffer holds them in.
 */
fun timeline(entries: List<TimelineEntry>, filter: TimelineFilter, tail: Int? = null): List<TimelineEntry> =
    entries.distinctBy { it.kind to it.id }.filter(filter::admits).sortedBy { it.epochNanos }.let { all -> tail?.let(all::takeLast) ?: all }

/** Attributes the text form shows as columns, or leaves out as the same on every line of a process. */
private val COLUMNS = setOf(Attr.ROLE, Attr.PEER, Attr.SERVICE, Attr.BOARD, Attr.COMMIT, Attr.PID, Attr.SESSION, Attr.REPLICA, Attr.EVENT, LOGGER_NAME_ATTRIBUTE)

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** [entry] as one line: time, role, peer, kind, name, then its own attributes. */
fun TimelineEntry.text(zone: ZoneId): String = buildString {
    append(TIME.format(Instant.ofEpochSecond(0, epochNanos).atZone(zone)))
    append("  ").append(role.padEnd(6))
    append(" ").append(peer.take(PEER_WIDTH).padEnd(PEER_WIDTH))
    append("  ").append(
        when (kind) {
            TimelineEntry.Kind.LOG -> (severity ?: "LOG").padEnd(5)
            TimelineEntry.Kind.EVENT -> "event"
            TimelineEntry.Kind.SPAN -> "span "
        },
    )
    append("  ").append(name)
    durationNanos?.let { append(" (").append(it / NANOS_PER_MILLI).append(" ms)") }
    if (kind == TimelineEntry.Kind.LOG) attributes[LOGGER_NAME_ATTRIBUTE]?.let { append("  [").append(it.substringAfterLast('.')).append(']') }
    attributes.filterKeys { it !in COLUMNS }.forEach { (key, value) -> append("  ").append(key).append('=').append(value) }
}

/** [entry] as one JSON object, for a line of JSONL. */
fun TimelineEntry.json(): String = buildJsonObject {
    put("time", Instant.ofEpochSecond(0, epochNanos).toString())
    put("epochNanos", epochNanos)
    put("kind", kind.name.lowercase())
    put("name", name)
    put("role", role)
    put("peer", peer)
    severity?.let { put("severity", it) }
    durationNanos?.let { put("durationNanos", it) }
    putJsonObject("attributes") { attributes.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }
}.toString()

private const val PEER_WIDTH = 28
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Reads the telemetry directories a bee home holds: a bridge's under `telemetry/`, and a host's
 * under each data dir's `telemetry/` (bee-up keeps the host's in `host/`).
 */
fun telemetryDirs(home: Path): List<Path> {
    val bridges = home.resolve("telemetry").takeIf { it.isDirectory() }?.listDirectoryEntries()?.filter { it.resolve("buffer").isDirectory() }.orEmpty()
    val hosts = home.takeIf { it.isDirectory() }?.listDirectoryEntries()?.map { it.resolve("telemetry") }?.filter { it.resolve("buffer").isDirectory() }.orEmpty()
    return (hosts + bridges).sorted()
}

/**
 * Every entry the buffer in [dir] holds, read from a copy taken into [scratch]: recovering a
 * buffer can write to it, and its own process may still be writing. The store only ever renames a
 * finished file into place, so each file copied is whole. A copy taken mid-write may lack the
 * newest records, as an older snapshot would; read again for them. A file the writer renames or
 * deletes between the listing and the copy is skipped, and its `.tmp` files are never read.
 *
 * @throws IllegalArgumentException if [dir] holds no buffer
 */
fun readBuffer(dir: Path, scratch: Path): List<TimelineEntry> {
    val source = dir.resolve("buffer")
    require(source.isDirectory()) { "$dir holds no telemetry buffer: there is no $source." }
    val copy = Files.createTempDirectory(scratch, "buffer-")
    source.listDirectoryEntries().filter { it.isRegularFile() && !it.fileName.toString().endsWith(".tmp") }.forEach { file ->
        try {
            file.copyTo(copy.resolve(file.fileName))
        } catch (_: NoSuchFileException) {
            // Gone since the listing: renamed over or retired by the writer. Recovery copes with a missing segment.
        }
    }
    val warp = WarpTelemetry(ReplicaId("bee-telemetry-reader"), FileChannelDurableStore(copy.toFile()))
    runBlocking { warp.recover() }
    return warp.logs.snapshot().toList().map(TimelineEntry::of) + warp.spans.snapshot().elements.map(TimelineEntry::of)
}

/** A scratch directory for [readBuffer]'s copies, deleted as the JVM exits. */
fun scratchDir(): Path = Files.createTempDirectory("bee-telemetry-read-").also { dir ->
    Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
}
