package us.tractat.bee.telemetry

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.ExportResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.SpanStatus
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.LOGGER_NAME_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogCapture
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.otel.logging.NormalizedLogEvent
import us.tractat.kuilt.otel.logging.ResolvedLogEvent
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Attributes every record of one process carries: its service, role, peer, board and commit.
 * A process learns some late (a bridge learns its board from the host), so [set] adds one for
 * every record from then on.
 */
class TelemetryResource(initial: Map<String, String>) {
    private val attributes = MutableStateFlow(Redaction.scrub(initial) + (Attr.COMMIT to BuildInfo.commit))

    /** Stamps [key] = [value] on every record from now on. */
    fun set(key: String, value: String) = attributes.update { it + (key to Redaction.scrub(value)) }

    /** The attributes as they are now. */
    fun snapshot(): Map<String, String> = attributes.value
}

/**
 * [BeeTelemetry] into a kuilt [WarpTelemetry]: events and captured log lines into its log buffer,
 * spans into its span buffer, each stamped with [resource]. Invariant: **a record is built on
 * the caller and written on [scope]**: the caller pays a scrub, an allocation and a queue offer,
 * and nothing else. One drain on [scope] recovers the buffer, then writes what the queue holds in
 * batches. The queue holds [capacity] records and drops its oldest when full.
 *
 * A failed write drops its batch and is logged once, until a write succeeds again (kuilt's
 * once-per-outage rule). kuilt's recovery never throws: a buffer it can't read, it logs and
 * starts afresh without overwriting what the store holds.
 *
 * [scope] should run on an IO dispatcher: the JVM's file store blocks its thread to write.
 */
class RecordingTelemetry(
    private val warp: WarpTelemetry,
    val resource: TelemetryResource,
    private val clock: Clock,
    private val random: Random,
    scope: CoroutineScope,
    capacity: Int = QUEUE_CAPACITY,
) : BeeTelemetry {
    private sealed interface Item {
        class Record(val record: LogRecord) : Item

        class Captured(val event: ResolvedLogEvent) : Item

        class Span(val span: SpanRecord) : Item
    }

    private val queue = Channel<Item>(capacity, BufferOverflow.DROP_OLDEST)

    /** Maps a captured log line as kuilt does, plus [resource], with every value scrubbed. */
    private val capture = LogCapture(
        warp.logs,
        CaptureConfig(
            minLevel = LogLevel.TRACE,
            attributeMapper = { event ->
                resource.snapshot() + Redaction.scrub(event.attributes) + (LOGGER_NAME_ATTRIBUTE to event.loggerName)
            },
        ),
        clock,
        random,
    )

    // Touched only by the drain.
    private var writeFailing = false

    private val recovery = CompletableDeferred<Unit>()

    /** Completes once the buffer has recovered: nothing may read or drain it before. */
    val recovered: Job get() = recovery

    private val drain = scope.launch {
        warp.recover()
        recovery.complete(Unit)
        for (first in queue) {
            val batch = buildList {
                add(first)
                while (size < BATCH) add(queue.tryReceive().getOrNull() ?: break)
            }
            write(batch)
        }
    }

    override fun event(name: String, attributes: Map<String, String>) = offer {
        val now = clock.now().epochNanos()
        Item.Record(
            LogRecord(
                recordId = bytes(RECORD_ID_BYTES),
                severityNumber = INFO_SEVERITY,
                severityText = "INFO",
                body = name,
                attributes = resource.snapshot() + Redaction.scrub(attributes) + (Attr.EVENT to name),
                timestampEpochNanos = now,
                observedEpochNanos = now,
            ),
        )
    }

    override fun span(name: String, start: Instant, end: Instant, attributes: Map<String, String>, error: String?) = offer {
        Item.Span(
            SpanRecord(
                traceId = bytes(TRACE_ID_BYTES),
                spanId = bytes(SPAN_ID_BYTES),
                parentSpanId = null,
                name = name,
                kind = SpanKind.INTERNAL,
                startEpochNanos = start.epochNanos(),
                endEpochNanos = end.epochNanos(),
                attributes = resource.snapshot() + Redaction.scrub(attributes),
                status = error?.let { SpanStatus.Error(Redaction.scrub(it)) } ?: SpanStatus.Ok,
            ),
        )
    }

    /**
     * Records a log line a capture edge saw, its message and attributes already as they are to be
     * kept. Resolved here, on the caller, as kuilt's edges do, so it is stamped with the time it
     * was logged.
     */
    fun captured(event: NormalizedLogEvent) = offer { capture.resolveAtEdge(event.scrubbed())?.let(Item::Captured) }

    /**
     * Stops taking records and gives the drain up to [within] to write what it holds, then stops
     * it. Call it once, as the process ends.
     */
    suspend fun close(within: Duration) {
        queue.close()
        withTimeoutOrNull(within) { drain.join() }
        drain.cancel()
    }

    private inline fun offer(build: () -> Item?) {
        try {
            build()?.let(queue::trySend)
        } catch (@Suppress("TooGenericExceptionCaught") bug: Exception) {
            // Recording never throws into the caller; a record that can't be built is lost.
            reportOnce(bug) { "A telemetry record couldn't be built, so it was dropped." }
        }
    }

    private suspend fun write(batch: List<Item>) {
        val records = batch.filterIsInstance<Item.Record>().map { it.record }
        val captured = batch.filterIsInstance<Item.Captured>().map { it.event }
        val spans = batch.filterIsInstance<Item.Span>().map { it.span }
        if (records.isNotEmpty()) attempt(WRITE_FAILED) { warp.logs.export(records).orThrow() }
        if (captured.isNotEmpty()) attempt(WRITE_FAILED) { capture.captureAll(captured)?.orThrow() }
        for (span in spans) attempt(WRITE_FAILED) { warp.spans.export(span).orThrow() }
    }

    /** Runs [block], reporting its first failure since the last success. */
    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
            writeFailing = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            if (!writeFailing) {
                writeFailing = true
                log.warn(failure) { "Telemetry couldn't $what." }
            }
        }
    }

    private fun reportOnce(bug: Throwable, message: () -> String) {
        if (!buildFailing) {
            buildFailing = true
            log.warn(bug, message)
        }
    }

    @kotlin.concurrent.Volatile
    private var buildFailing = false

    private fun bytes(count: Int) = ByteString(random.nextBytes(count))

    private fun NormalizedLogEvent.scrubbed() = copy(message = message?.let(Redaction::scrub))

    private fun ExportResult.orThrow() {
        if (this is ExportResult.Failure) throw cause
    }

    companion object {
        /** How many records wait to be written before the oldest is dropped. */
        const val QUEUE_CAPACITY: Int = 4_096

        /** At most this many records go to the buffer in one write. */
        private const val BATCH = 256

        private const val RECORD_ID_BYTES = 8
        private const val TRACE_ID_BYTES = 16
        private const val SPAN_ID_BYTES = 8

        /** OpenTelemetry's INFO severity number. */
        private const val INFO_SEVERITY = 9

        private const val WRITE_FAILED = "write to its buffer; records are dropped until a write succeeds"

        /**
         * Telemetry's own logger. Its name is under [LOGGER_PREFIX], which every capture edge
         * skips: a line about a failed write, captured, would be written to the failing buffer.
         */
        private val log = KotlinLogging.logger("$LOGGER_PREFIX.RecordingTelemetry")

        /** Loggers no capture edge records: telemetry's own, and kuilt-otel's. */
        const val LOGGER_PREFIX: String = "us.tractat.bee.telemetry"
    }
}

/** This instant in nanoseconds since the epoch, OTLP's unit. */
internal fun Instant.epochNanos(): Long = epochSeconds * NANOS_PER_SECOND + nanosecondsOfSecond

private const val NANOS_PER_SECOND = 1_000_000_000L
