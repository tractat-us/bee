package us.tractat.bee.host

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import us.tractat.kuilt.test.assertAll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun publicUrlBindsAllInterfacesBehindItsProxy() {
        val parsed = HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--port", "8233"), home)
        val behindTheProxy = Arrival("172.19.0.2", 8233)
        assertAll(
            { assertEquals(Reach.Public(PublicUrl.parse("https://bee.tractat.us/")), parsed.reach) },
            { assertEquals("0.0.0.0", parsed.bind) },
            { assertEquals(PeerAddress.FORWARDED, parsed.reach.peers()) },
            { assertEquals(PeerAddress.DIRECT, Reach.Lan.peers(), "no proxy on the LAN") },
            {
                val headers = headersOf(HttpHeaders.Host to listOf("bee.tractat.us"), HttpHeaders.Origin to listOf("https://bee.tractat.us"))
                assertNull(parsed.siteRule().refusal("/", headers, behindTheProxy))
            },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--lan"), home) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/?k=fake"), home) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url"), home) } },
        )
    }

    @Test
    fun hostNameAddsANameTheHostAnswersTo() {
        val named = HostConfig.fromArgs(listOf("--host-name", "bee-lan.test"), home)
        val local = Arrival("127.0.0.1", HostConfig.DEFAULT_PORT)
        assertAll(
            { assertEquals(setOf("bee-lan.test"), named.hostNames) },
            { assertEquals(emptySet(), HostConfig.fromArgs(emptyList(), home).hostNames, "none by default") },
            { assertNull(named.siteRule().refusal("/", headersOf(HttpHeaders.Host, "bee-lan.test:8233"), local)) },
            { assertNotNull(HostConfig(dataDir = home).siteRule().refusal("/", headersOf(HttpHeaders.Host, "bee-lan.test:8233"), local), "not without the flag") },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--host-name", "a", "--host-name", "b"), home) } },
        )
    }

    @Test
    fun newLinkAndForgetDevicesSetTheKeyReset() {
        fun reset(vararg args: String) = HostConfig.fromArgs(args.toList(), home).keyReset
        assertAll(
            { assertEquals(KeyReset.Keep, reset("--lan"), "keep every key by default") },
            { assertEquals(KeyReset.NewLink, reset("--new-link", "--lan")) },
            { assertEquals(KeyReset.ForgetDevices, reset("--forget-devices")) },
            { assertEquals(KeyReset.ForgetDevices, reset("--new-link", "--forget-devices"), "forgetting mints a new link anyway") },
            { assertEquals(9000, HostConfig.fromArgs(listOf("--new-link", "--port", "9000"), home).port, "a switch takes no value") },
            { assertFailsWith<IllegalArgumentException> { reset("--new-link", "--new-link") } },
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
