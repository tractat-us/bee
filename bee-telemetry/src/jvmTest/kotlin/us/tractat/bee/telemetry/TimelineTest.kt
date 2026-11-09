package us.tractat.bee.telemetry

import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TimelineTest {
    private val home: Path = Files.createTempDirectory("bee-timeline-test")
    private val on: (String) -> String? = { null }

    @AfterTest
    fun cleanUp() = home.toFile().deleteRecursively().let { }

    /** A clock a test steps by hand, so records from two processes interleave in a known order. */
    private class Stepped(var now: Instant) : kotlin.time.Clock {
        override fun now(): Instant = now
    }

    /**
     * A host and a bridge record into a bee home as bee-up lays it out. The reader finds both
     * directories and prints one timeline, interleaved by time, each line its own process's.
     */
    @Test
    fun aHostAndABridgeReadAsOneTimeline() {
        val clock = Stepped(Instant.fromEpochSeconds(1_800_000_000))
        val host = ProcessTelemetry.open(listOf(home.resolve("host/telemetry")), mapOf(Attr.ROLE to Role.HOST, Attr.PEER to "host-1"), env = on, clock = clock, captureLogs = false)
        val bridge = ProcessTelemetry.open(listOf(home.resolve("telemetry/bridge-ada")), mapOf(Attr.ROLE to Role.BRIDGE, Attr.PEER to "agent:ada#1"), env = on, clock = clock, captureLogs = false)
        host.telemetry.event("board.restored")
        clock.now += 10.milliseconds
        bridge.telemetry.span("mcp.tool", clock.now, clock.now + 5.milliseconds, mapOf(Attr.TOOL to "bee_surface_open", Attr.SURFACE to "lunch"))
        clock.now += 10.milliseconds
        host.telemetry.event("surface.open", Attr.SURFACE to "lunch")
        host.close()
        bridge.close()

        val dirs = telemetryDirs(home)
        val scratch = Files.createDirectories(home.resolve("scratch"))
        val merged = timeline(dirs.flatMap { readBuffer(it, scratch) }, TimelineFilter())
        val lines = merged.map { it.text(ZoneOffset.UTC) }
        assertAll(
            { assertEquals(listOf(home.resolve("host/telemetry"), home.resolve("telemetry/bridge-ada")), dirs) },
            { assertEquals(listOf("board.restored", "mcp.tool", "surface.open"), merged.map { it.name }) },
            { assertEquals(listOf(Role.HOST, Role.BRIDGE, Role.HOST), merged.map { it.role }) },
            { assertTrue(lines[1].contains("span   mcp.tool (5 ms)") && lines[1].contains("bee.tool=bee_surface_open"), lines[1]) },
            { assertTrue(lines[2].startsWith("08:00:00.020  host   host-1"), lines[2]) },
            { assertEquals(listOf("mcp.tool"), timeline(merged, TimelineFilter(peer = "ada")).map { it.name }, "--peer") },
            { assertEquals(listOf("board.restored", "surface.open"), timeline(merged, TimelineFilter(role = Role.HOST)).map { it.name }, "--role") },
            { assertEquals(listOf("mcp.tool", "surface.open"), timeline(merged, TimelineFilter(sinceEpochNanos = merged[1].epochNanos)).map { it.name }, "--since") },
            { assertEquals(listOf("surface.open"), timeline(merged, TimelineFilter(), tail = 1).map { it.name }, "--tail") },
            { assertEquals(listOf("mcp.tool"), timeline(merged, TimelineFilter(role = Role.BRIDGE), tail = 1).map { it.name }, "--tail after the filter") },
        )
    }

    /**
     * Reading a buffer its process still holds open leaves every byte of it as it was, and the
     * process records on; a second read sees what it recorded since.
     */
    @Test
    fun readingALiveBufferLeavesItUntouched() {
        val dir = home.resolve("host/telemetry")
        val scratch = Files.createDirectories(home.resolve("scratch"))
        val live = ProcessTelemetry.open(listOf(dir), mapOf(Attr.ROLE to Role.HOST), env = on, captureLogs = false)
        try {
            live.telemetry.event("board.restored")
            eventually { readBuffer(dir, scratch).any { it.name == "board.restored" } }
            val before = snapshot(dir.resolve("buffer"))
            readBuffer(dir, scratch)
            val after = snapshot(dir.resolve("buffer"))
            live.telemetry.event("surface.open")
            eventually { readBuffer(dir, scratch).any { it.name == "surface.open" } }
            assertEquals(before, after, "the read changed the live buffer")
        } finally {
            live.close()
        }
    }

    @Test
    fun aDirectoryWithNoBufferIsAnErrorNotAnEmptyTimeline() {
        val bridge = home.resolve("telemetry/bridge-ada")
        ProcessTelemetry.open(listOf(bridge), mapOf(Attr.ROLE to Role.BRIDGE), env = on, captureLogs = false).close()
        val scratch = Files.createDirectories(home.resolve("scratch"))
        assertAll(
            { assertFailsWith<IllegalArgumentException> { readBuffer(bridge.resolve("buffer"), scratch) } },
            { assertEquals(emptyList(), telemetryDirs(home.resolve("telemetry/bridge-ada")), "a bridge's own folder isn't a home") },
        )
    }

    private fun snapshot(dir: Path): Map<String, List<Byte>> =
        Files.list(dir).use { files -> files.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() } }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not within 10 s" }
            Thread.sleep(20)
        }
    }

    /** Reading twice copies twice; each record is still printed once. */
    @Test
    fun aRecordInTwoCopiesIsPrintedOnce() {
        val dir = home.resolve("host/telemetry")
        ProcessTelemetry.open(listOf(dir), mapOf(Attr.ROLE to Role.HOST), env = on, captureLogs = false).use { it.telemetry.event("board.restored") }
        val scratch = Files.createDirectories(home.resolve("scratch"))
        val twice = readBuffer(dir, scratch.resolve("a").also(Files::createDirectories)) + readBuffer(dir, scratch.resolve("b").also(Files::createDirectories))
        assertEquals(1, timeline(twice, TimelineFilter()).size)
    }

    @Test
    fun theCommandLineReadsItsFilters() {
        val now = Instant.fromEpochSeconds(1_800_000_000)
        val options = TimelineOptions.parse(listOf("--role", "bridge", "--peer", "ada", "--since", "10m", "--tail", "50", "--json"), env = { null }, clock = Stepped(now))
        assertAll(
            { assertEquals(TimelineFilter(role = "bridge", peer = "ada", sinceEpochNanos = (now - 10.minutes).epochNanos()), options.filter) },
            { assertEquals(50, options.tail) },
            { assertTrue(options.json) },
            { assertEquals(Path.of("/somewhere"), TimelineOptions.parse(emptyList(), env = { if (it == "BEE_HOME") "/somewhere" else null }, clock = Stepped(now)).home) },
            { assertFailsWith<IllegalArgumentException> { TimelineOptions.parse(listOf("--since", "soon"), env = { null }, clock = Stepped(now)) } },
            { assertFailsWith<IllegalArgumentException> { TimelineOptions.parse(listOf("--loud"), env = { null }, clock = Stepped(now)) } },
        )
    }
}
