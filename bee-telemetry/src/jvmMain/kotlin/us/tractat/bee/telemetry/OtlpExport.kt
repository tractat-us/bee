package us.tractat.bee.telemetry

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.OtlpEdge
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.otel.otlp.OtlpHttpEdge
import us.tractat.kuilt.otel.otlp.OtlpWireFormat
import us.tractat.kuilt.store.DurableStore
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends a process's buffer on to an OpenTelemetry collector, when `BEE_OTLP_ENDPOINT` names one.
 * Off by default. kuilt's bridge sends only what the collector hasn't had ([WarpOtlpBridge]), so
 * a collector that was down gets the gap once it is back, and the buffer is never emptied by it:
 * the local record stands whether or not anything is sent.
 *
 * A failed send is logged once, until a send succeeds. It never touches recording: sends wait for
 * the buffer to recover, run on telemetry's own thread while the process runs (the last one runs
 * on the closing thread), and time out after 5 s.
 */
class OtlpExport(private val warp: WarpTelemetry, private val edge: OtlpEdge, private val clock: Clock, private val client: HttpClient? = null) : AutoCloseable {
    private val bridge = WarpOtlpBridge(warp, clock)
    private var failing = false

    /** Sends what [edge] lacks, once. Never throws but for cancellation. */
    suspend fun send() {
        val result = try {
            bridge.drain(edge)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") bug: Exception) {
            report("OTLP export failed: ${Redaction.scrub(bug.toString())}")
            return
        }
        when (result) {
            is DrainResult.Success -> failing = false
            is DrainResult.Partial -> report("OTLP export sent only part: ${result.failures.joinToString { "${it.signal}: ${Redaction.scrub(it.cause.toString())}" }}")
            is DrainResult.Failure -> report("OTLP export failed: ${result.failures.joinToString { "${it.signal}: ${Redaction.scrub(it.cause.toString())}" }}")
        }
    }

    /** Sends every [period] on [scope], once [ready] completes, until [scope] ends. */
    fun every(period: Duration, scope: CoroutineScope, ready: Job): Job = scope.launch {
        ready.join()
        while (true) {
            send()
            delay(period)
        }
    }

    /** Closes the HTTP client this export sends through, if it owns one. */
    override fun close() {
        client?.close()
    }

    private fun report(line: String) {
        if (failing) return
        failing = true
        log.warn { "$line. It is tried again every ${PERIOD.inWholeSeconds} s, and reported again only after a send succeeds." }
    }

    companion object {
        /** Where to send: an OTLP/HTTP collector's base URL, such as `http://127.0.0.1:4318`. Unset, nothing is sent. */
        const val ENDPOINT: String = "BEE_OTLP_ENDPOINT"

        /** `json` (the default) or `protobuf`: the OTLP/HTTP encoding. */
        const val PROTOCOL: String = "BEE_OTLP_PROTOCOL"

        /** How often the buffer is offered to the collector. */
        val PERIOD: Duration = 10.seconds

        private val REQUEST_TIMEOUT = 5.seconds
        private val log = KotlinLogging.logger("${RecordingTelemetry.LOGGER_PREFIX}.OtlpExport")

        /**
         * An [OtlpExport] to the collector [env] names, remembering what it sent in the store [sent]
         * opens, or null when [env] names none, in which case [sent] is never called.
         *
         * @throws IllegalArgumentException if [PROTOCOL] is neither `json` nor `protobuf`
         */
        fun fromEnv(env: (String) -> String?, warp: WarpTelemetry, sent: () -> DurableStore, clock: Clock): OtlpExport? {
            val endpoint = env(ENDPOINT)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val wire = when (env(PROTOCOL)?.trim()?.lowercase()) {
                null, "", "json", "http/json" -> OtlpWireFormat.JSON
                "protobuf", "http/protobuf" -> OtlpWireFormat.PROTOBUF
                else -> throw IllegalArgumentException("$PROTOCOL is ${env(PROTOCOL)}: it takes json or protobuf.")
            }
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
                    connectTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
                }
            }
            log.info { "Telemetry goes on to the OTLP collector at ${Redaction.scrub(endpoint)}, as $wire." }
            return OtlpExport(warp, OtlpHttpEdge(client, endpoint, sent(), wire = wire), clock, client)
        }
    }
}
