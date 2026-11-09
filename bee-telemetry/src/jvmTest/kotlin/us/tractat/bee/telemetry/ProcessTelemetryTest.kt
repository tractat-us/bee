package us.tractat.bee.telemetry

import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ProcessTelemetryTest {
    private val root: Path = Files.createTempDirectory("bee-telemetry-test")
    private val resource = mapOf(Attr.SERVICE to "bee-host", Attr.ROLE to Role.HOST, Attr.PEER to "host-1234")
    private val on: (String) -> String? = { null }

    @AfterTest
    fun cleanUp() = root.toFile().deleteRecursively().let { }

    @Test
    fun anEventIsKeptWithTheProcessesPeerAttributes() {
        val dir = root.resolve("host")
        ProcessTelemetry.open(listOf(dir), resource, env = on, captureLogs = false).use { opened ->
            opened.resource.set(Attr.BOARD, "b-42")
            opened.telemetry.event("surface.open", Attr.SURFACE to "lunch")
        }
        val record = Stored(dir).logs.single { it.attributes[Attr.EVENT] == "surface.open" }
        assertAll(
            { assertEquals("surface.open", record.body) },
            { assertEquals("lunch", record.attributes[Attr.SURFACE]) },
            { assertEquals(Role.HOST, record.attributes[Attr.ROLE], "the role it recorded as") },
            { assertEquals("host-1234", record.attributes[Attr.PEER], "the peer it recorded as") },
            { assertEquals("b-42", record.attributes[Attr.BOARD], "a resource attribute set after opening") },
            { assertEquals(BuildInfo.commit, record.attributes[Attr.COMMIT]) },
            { assertNotNull(record.timestampEpochNanos) },
        )
    }

    @Test
    fun aSpanIsKeptWithItsDurationAndOutcome() {
        val dir = root.resolve("bridge")
        val start = Clock.System.now()
        ProcessTelemetry.open(listOf(dir), resource, env = on, captureLogs = false).use {
            it.telemetry.span("mcp.tool", start, start + 40.milliseconds, mapOf(Attr.TOOL to "bee_board"), error = "IllegalArgumentException")
        }
        val span = Stored(dir).spans.single()
        assertAll(
            { assertEquals("mcp.tool", span.name) },
            { assertEquals(40_000_000L, span.endEpochNanos - span.startEpochNanos) },
            { assertEquals("bee_board", span.attributes[Attr.TOOL]) },
            { assertEquals("host-1234", span.attributes[Attr.PEER]) },
            { assertTrue("Error" in span.status.toString(), "status: ${span.status}") },
        )
    }

    /**
     * The key reaches telemetry four ways a host would hand it over: a logged join link, an
     * exception's message, an event attribute, and a span's. None of the stored bytes hold it, and
     * the scrubbed lines are there, so the scan isn't of an empty buffer.
     */
    @Test
    fun noKeyEverReachesTheStoredBytes() {
        val key = Base64Key.of(Random(7))
        val dir = root.resolve("keys")
        ProcessTelemetry.open(listOf(dir), resource, env = on).use {
            val log = LoggerFactory.getLogger("us.tractat.bee.host.MainKt")
            log.info("On your network: http://laptop.local:8233/. Join link, no code: http://laptop.local:8233/?k={}", key)
            log.warn("dial failed", IOException("connect timeout to ws://10.0.0.2:8233/ws/board?board=b&k=$key"))
            it.telemetry.event("gate.refused", "presented" to key)
            it.telemetry.span("mcp.tool", Clock.System.now(), Clock.System.now(), mapOf("url" to "/mcp?k=$key"))
        }
        val stored = Stored(dir)
        val bytes = Files.walk(dir).use { files -> files.filter { it.isRegularFile() }.map { it.readBytes().decodeToString() }.toList() }
        assertAll(
            { assertTrue(bytes.none { key in it }, "a stored file holds the key") },
            { assertTrue(stored.logs.any { it.body == "On your network: http://laptop.local:8233/. Join link, no code: http://laptop.local:8233/?k=<redacted>" }, "the join link line, scrubbed: ${stored.logs.map { it.body }}") },
            { assertTrue(stored.logs.any { it.attributes["exception.message"]?.contains("k=<redacted>") == true }, "the exception, scrubbed") },
            { assertEquals(Redaction.REDACTED, stored.logs.single { it.body == "gate.refused" }.attributes["presented"]) },
            { assertEquals("/mcp?k=<redacted>", stored.spans.single().attributes["url"]) },
        )
    }

    /** The SDK's own failure lines can quote a whole tool call: none of its words are kept. */
    @Test
    fun anMcpSdkLineKeepsNoneOfItsWords() {
        val dir = root.resolve("sdk")
        ProcessTelemetry.open(listOf(dir), resource, env = on).use {
            val sdk = LoggerFactory.getLogger("io.modelcontextprotocol.kotlin.sdk.shared.ReadBuffer")
            sdk.error("Failed to deserialize message from line: {\"method\":\"tools/call\",\"params\":{\"arguments\":{\"title\":\"secret plans\"}}}", IllegalStateException("JSON input: secret plans"))
        }
        val stored = Stored(dir)
        val bytes = Files.walk(dir).use { files -> files.filter { it.isRegularFile() }.map { it.readBytes().decodeToString() }.toList() }
        val line = stored.logs.single { it.attributes["logger.name"]?.startsWith("io.modelcontextprotocol") == true }
        assertAll(
            { assertTrue(bytes.none { "secret plans" in it }, "a request's words are in a stored file") },
            { assertEquals(LogbackEdge.WITHHELD_MESSAGE, line.body) },
            { assertEquals("ERROR", line.severityText) },
            { assertEquals("java.lang.IllegalStateException", line.attributes["exception.type"]) },
        )
    }

    @Test
    fun theKillSwitchOpensNothingAndCapturesNothing() {
        val dir = root.resolve("off")
        val opened = ProcessTelemetry.open(listOf(dir), resource, env = { if (it == ProcessTelemetry.SWITCH) "off" else null })
        opened.use {
            assertAll(
                { assertSame(BeeTelemetry.Off, it.telemetry) },
                { assertNull(it.dir) },
                { assertFalse(Files.exists(dir), "no directory made") },
                { assertNull(rootLogger().getAppender("bee-telemetry"), "no log edge attached") },
            )
        }
    }

    @Test
    fun aSecondProcessOnTheSameDirectoryTakesTheNextOne() {
        val first = root.resolve("bridge-ada")
        val second = root.resolve("bridge-ada-2")
        ProcessTelemetry.open(listOf(first, second), resource, env = on, captureLogs = false).use { a ->
            ProcessTelemetry.open(listOf(first, second), resource, env = on, captureLogs = false).use { b ->
                assertAll(
                    { assertEquals(first, a.dir) },
                    { assertEquals(second, b.dir) },
                )
            }
        }
    }

    /** A store that refuses every read and write: the process records on, unharmed and unslowed. */
    @Test
    fun aFailingStoreNeverReachesTheCaller() {
        val refusing = object : DurableStore {
            override suspend fun read(key: StoreKey): ByteArray? = throw IOException("disk gone")

            override suspend fun write(key: StoreKey, bytes: ByteArray) = throw IOException("disk gone")

            override suspend fun delete(key: StoreKey) = throw IOException("disk gone")
        }
        val opened = ProcessTelemetry.open(listOf(root.resolve("broken")), resource, env = on, store = { refusing })
        val spent = measureTime {
            opened.use {
                repeat(10_000) { n -> it.telemetry.event("surface.update", Attr.SURFACE to "s-$n") }
                LoggerFactory.getLogger("us.tractat.bee.host.Test").info("still logging")
            }
        }
        assertAll(
            { assertTrue(opened.telemetry !== BeeTelemetry.Off, "it opened, and the failure came later") },
            { assertTrue(spent < 5.seconds, "recording and closing took $spent") },
            { assertNull(rootLogger().getAppender("bee-telemetry"), "close detached the edge") },
        )
    }

    private fun rootLogger() = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)
}

/** What a closed telemetry directory holds, read back as the next process would. */
private class Stored(dir: Path) {
    private val warp = us.tractat.kuilt.otel.WarpTelemetry(
        us.tractat.kuilt.crdt.ReplicaId("reader"),
        us.tractat.kuilt.store.FileChannelDurableStore(dir.resolve("buffer").toFile()),
    ).also { runBlocking { it.recover() } }

    val logs: List<LogRecord> = warp.logs.snapshot().toList()
    val spans: List<SpanRecord> = warp.spans.snapshot().elements.toList()
}

/** A key as Pairing mints one: 32 random bytes, base64url without padding. */
private object Base64Key {
    fun of(random: Random): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(32))
}
