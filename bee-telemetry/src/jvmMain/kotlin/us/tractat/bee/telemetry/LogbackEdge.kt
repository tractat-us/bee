package us.tractat.bee.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import us.tractat.kuilt.otel.logging.EXCEPTION_MESSAGE_ATTRIBUTE
import us.tractat.kuilt.otel.logging.LogLevel
import us.tractat.kuilt.otel.logging.NormalizedLogEvent

/**
 * Captures every line logback writes into [recording], kotlin-logging's and every library's, with
 * its message and attributes scrubbed ([Redaction]). It sits beside the console appender, so
 * `host.log` and stderr read exactly as before.
 *
 * Why bee's own edge rather than kuilt's `installLogbackCapture`: kuilt's edges keep a line's
 * message as it was logged and offer no hook to scrub it, and the host logs its join link, key
 * and all (tractat-us/kuilt#3152). This edge maps a line as kuilt's does, scrubs it, then hands it
 * to the same kuilt capture core.
 *
 * Skips telemetry's own loggers and kuilt-otel's ([RecordingTelemetry.LOGGER_PREFIX]): a line
 * about a failed write, captured, would go to the failing buffer.
 */
class LogbackEdge(private val recording: RecordingTelemetry) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val name = event.loggerName
        if (SKIPPED.any(name::startsWith)) return
        val level = event.level.toLogLevel() ?: return
        val attributes = buildMap {
            event.mdcPropertyMap?.forEach { (key, value) -> if (value != null) put(key, value) }
            event.throwableProxy?.let { thrown ->
                put(EXCEPTION_TYPE, thrown.className)
                thrown.message?.let { put(EXCEPTION_MESSAGE_ATTRIBUTE, it) }
            }
        }
        recording.captured(NormalizedLogEvent(level, name, event.formattedMessage, attributes, emittedEpochNanos = event.instant.epochNanos()))
    }

    /** Detaches this edge from [context]'s root logger and stops it. */
    fun uninstall(context: LoggerContext) {
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this)
        stop()
    }

    companion object {
        private const val EXCEPTION_TYPE = "exception.type"
        private val SKIPPED = listOf(RecordingTelemetry.LOGGER_PREFIX, "us.tractat.kuilt.otel")

        /**
         * Attaches an edge into [recording] to the root logger of the process's logback context,
         * or does nothing and returns null if SLF4J isn't bound to logback.
         */
        fun install(recording: RecordingTelemetry): Pair<LoggerContext, LogbackEdge>? {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
            val edge = LogbackEdge(recording).apply {
                this.context = context
                name = "bee-telemetry"
                start()
            }
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(edge)
            return context to edge
        }
    }
}

private fun java.time.Instant.epochNanos(): Long = epochSecond * 1_000_000_000L + nano

private fun Level.toLogLevel(): LogLevel? = when (toInt()) {
    Level.TRACE_INT -> LogLevel.TRACE
    Level.DEBUG_INT -> LogLevel.DEBUG
    Level.INFO_INT -> LogLevel.INFO
    Level.WARN_INT -> LogLevel.WARN
    Level.ERROR_INT -> LogLevel.ERROR
    else -> null
}
