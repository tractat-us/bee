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
            ProcessTelemetry.open(listOf(root.resolve("on")), resource, env = env::get, captureLogs = false).use {
                it.telemetry.event("socket.dial_failed", Attr.REASON to "ws://10.0.0.2:8233/ws/board?k=$key")
                val now = Clock.System.now()
                it.telemetry.span("mcp.tool", now, now + 3.milliseconds, mapOf(Attr.TOOL to "bee_board"))
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

    /** A collector that isn't there: recording carries on, and closing still takes about its grace. */
    @Test
    fun aCollectorThatIsDownCostsTheProcessNothing() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val env = mapOf(OtlpExport.ENDPOINT to "http://127.0.0.1:$closedPort")
        val dir = root.resolve("down")
        val spent = measureTime {
            ProcessTelemetry.open(listOf(dir), resource, env = env::get, captureLogs = false).use { opened ->
                repeat(100) { opened.telemetry.event("surface.update", Attr.SURFACE to "s-$it") }
            }
        }
        val kept = readBuffer(dir, Files.createDirectories(root.resolve("scratch")))
        assertAll(
            { assertTrue(spent < 6.seconds, "recording and closing took $spent") },
            { assertEquals(100, kept.count { it.name == "surface.update" }, "every record is still kept locally") },
        )
    }

    @Test
    fun aProtocolItCantSpeakSendsNothingAndRecordsOn() {
        val env = mapOf(OtlpExport.ENDPOINT to "http://127.0.0.1:1", OtlpExport.PROTOCOL to "grpc")
        ProcessTelemetry.open(listOf(root.resolve("grpc")), resource, env = env::get, captureLogs = false).use {
            assertTrue(it.telemetry !== BeeTelemetry.Off, "recording is still on")
        }
    }
}
