package us.tractat.bee.telemetry

import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import us.tractat.kuilt.test.assertAll
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class OtlpExportTest {
    private val root: Path = Files.createTempDirectory("bee-otlp-test")
    private val resource = mapOf(Attr.SERVICE to "bee-mcp", Attr.ROLE to Role.BRIDGE, Attr.PEER to "agent:ada#1")

    @AfterTest
    fun cleanUp() = root.toFile().deleteRecursively().let { }

    /** A collector that keeps each request's path and body. */
    private class Collector : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                post("/v1/{signal}") {
                    requests += call.request.uri to call.receiveText()
                    call.respond(HttpStatusCode.OK, "{}")
                }
            }
        }.also { it.start(wait = false) }

        val url: String = "http://127.0.0.1:${kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }}"

        override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    /**
     * With `BEE_OTLP_ENDPOINT` set, what a process recorded reaches the collector by the time it
     * closes: its event as a log record, its span as a trace, both with their peer attributes, and
     * no key. Without it, nothing is sent at all.
     */
    @Test
    fun aRecordReachesTheCollectorTheEnvironmentNames() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Random(5).nextBytes(32))
        Collector().use { collector ->
            val env = mapOf(OtlpExport.ENDPOINT to collector.url)
            ProcessTelemetry.open(listOf(root.resolve("on")), resource, env = env::get, captureLogs = false, timing = fast).use {
                it.telemetry.event("socket.dial_failed", Attr.REASON to "ws://10.0.0.2:8233/ws/board?k=$key")
                val now = Clock.System.now()
                it.telemetry.span("mcp.tool", now, now + 3.milliseconds, mapOf(Attr.TOOL to "bee_board"))
                eventually { collector.requests.any { r -> "socket.dial_failed" in r.second } && collector.requests.any { r -> "mcp.tool" in r.second } }
            }
            ProcessTelemetry.open(listOf(root.resolve("off")), resource, env = { null }, captureLogs = false).use {
                it.telemetry.event("never.sent")
            }
            val logs = collector.requests.filter { it.first == "/v1/logs" }.joinToString { it.second }
            val traces = collector.requests.filter { it.first == "/v1/traces" }.joinToString { it.second }
            assertAll(
                { assertTrue("socket.dial_failed" in logs, "the event: $logs") },
                { assertTrue("agent:ada#1" in logs, "with its peer") },
                { assertTrue("mcp.tool" in traces && "bee_board" in traces, "the span: $traces") },
                { assertTrue(collector.requests.none { key in it.second }, "a key reached the collector") },
                { assertTrue(collector.requests.none { "never.sent" in it.second }, "sent with no endpoint") },
            )
        }
    }

    /**
     * A collector that takes the connection and never answers, the worst a demo network can do:
     * recording carries on, closing stays within its two graces plus a margin, and every record
     * is still kept locally.
     */
    @Test
    fun aCollectorThatNeverAnswersCostsTheProcessNothing() {
        ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { silent ->
            val held = CopyOnWriteArrayList<java.net.Socket>()
            val accepting = Thread { runCatching { while (true) held += silent.accept() } }.apply { isDaemon = true; start() }
            val env = mapOf(OtlpExport.ENDPOINT to "http://127.0.0.1:${silent.localPort}")
            val dir = root.resolve("silent")
            val spent = measureTime {
                ProcessTelemetry.open(listOf(dir), resource, env = env::get, captureLogs = false, timing = fast).use { opened ->
                    repeat(100) { opened.telemetry.event("surface.update", Attr.SURFACE to "s-$it") }
                    Thread.sleep(500) // several periods: sends are in flight, hanging, as the process records on
                }
            }
            val kept = readBuffer(dir, Files.createDirectories(root.resolve("scratch")))
            assertAll(
                { assertTrue(held.isNotEmpty(), "the export never dialled the collector, so nothing was tested") },
                { assertTrue(spent < 3.seconds, "recording and closing took $spent") },
                { assertEquals(100, kept.count { it.name == "surface.update" }, "every record is still kept locally") },
            )
            held.forEach { it.close() }
            accepting.interrupt()
        }
    }

    /** A protocol bee can't speak: nothing reaches a live collector, and the record is still kept. */
    @Test
    fun aProtocolItCantSpeakSendsNothingAndRecordsOn() {
        Collector().use { collector ->
            val env = mapOf(OtlpExport.ENDPOINT to collector.url, OtlpExport.PROTOCOL to "grpc")
            val dir = root.resolve("grpc")
            ProcessTelemetry.open(listOf(dir), resource, env = env::get, captureLogs = false, timing = fast).use {
                it.telemetry.event("surface.open")
                Thread.sleep(300)
            }
            val kept = readBuffer(dir, Files.createDirectories(root.resolve("scratch")))
            assertAll(
                { assertEquals(emptyList(), collector.requests.toList(), "a request reached the collector") },
                { assertEquals(listOf("surface.open"), kept.map { it.name }) },
                { assertTrue(!Files.exists(dir.resolve("otlp-sent")), "a sent-set opened with nothing to send") },
            )
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not within 20 s" }
            Thread.sleep(20)
        }
    }

    private val fast = TelemetryTiming(closeGrace = 500.milliseconds, otlpPeriod = 50.milliseconds)
}
