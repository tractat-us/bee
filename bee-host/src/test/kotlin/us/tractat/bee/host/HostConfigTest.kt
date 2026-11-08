package us.tractat.bee.host

import us.tractat.kuilt.test.assertAll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostConfigTest {
    private val home = Path.of("/home/iain")

    @Test
    fun defaultBindsLoopbackOnly() {
        val parsed = HostConfig.fromArgs(listOf("--port", "9000"), home)
        assertAll(
            { assertEquals(Reach.Loopback, parsed.reach) },
            { assertEquals("127.0.0.1", parsed.bind) },
            { assertEquals("127.0.0.1", HostConfig(dataDir = home).bind, "the constructor's default too") },
        )
    }

    @Test
    fun lanFlagBindsAllInterfaces() {
        val parsed = HostConfig.fromArgs(listOf("--port", "9000", "--lan", "--data-dir", "/tmp/bee"), home)
        assertAll(
            { assertEquals(Reach.Lan, parsed.reach) },
            { assertEquals("0.0.0.0", parsed.bind) },
            { assertEquals(9000, parsed.port) },
            { assertEquals(Path.of("/tmp/bee"), parsed.dataDir) },
        )
    }

    @Test
    fun lanFlagTakesNoValueAndAppearsOnce() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--lan", "--lan"), home) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--lan", "yes"), home) } },
        )
    }

    @Test
    fun joinRequestsPerHourSetsThePairingBudget() {
        assertAll(
            { assertEquals(PairingBudget(5), HostConfig.fromArgs(listOf("--join-requests-per-hour", "5"), home).pairingBudget) },
            { assertEquals(PairingBudget(30), HostConfig.fromArgs(emptyList(), home).pairingBudget, "30 by default") },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--join-requests-per-hour", "0"), home) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--join-requests-per-hour", "many"), home) } },
        )
    }
}
