package us.tractat.bee.host

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import us.tractat.kuilt.test.assertAll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HostConfigTest {
    private val home = Path.of("/home/iain")

    @Test
    fun defaultBindsLoopbackOnly() {
        val parsed = HostConfig.fromArgs(listOf("--port", "9000"), home, NO_ENV)
        assertAll(
            { assertEquals(Reach.Loopback, parsed.reach) },
            { assertEquals("127.0.0.1", parsed.bind) },
            { assertEquals("127.0.0.1", HostConfig(dataDir = home).bind, "the constructor's default too") },
        )
    }

    @Test
    fun lanFlagBindsAllInterfaces() {
        val parsed = HostConfig.fromArgs(listOf("--port", "9000", "--lan", "--data-dir", "/tmp/bee"), home, NO_ENV)
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
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--lan", "--lan"), home, NO_ENV) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--lan", "yes"), home, NO_ENV) } },
        )
    }

    @Test
    fun publicUrlBindsAllInterfacesBehindItsProxy() {
        val parsed = HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--port", "8233"), home, OWNER_ENV)
        val behindTheProxy = Arrival("172.19.0.2", 8233)
        assertAll(
            { assertEquals(Reach.Public(PublicUrl.parse("https://bee.tractat.us/"), ForwardedPeer.AppendedForwardedFor, OwnerKey.parse(FAKE_OWNER_KEY)), parsed.reach) },
            { assertEquals("0.0.0.0", parsed.bind) },
            { assertEquals(ForwardedPeer.AppendedForwardedFor, parsed.reach.peers(), "a proxy that appends its client, without --client-ip-header") },
            { assertEquals(PeerAddress.DIRECT, Reach.Lan.peers(), "no proxy on the LAN") },
            {
                val headers = headersOf(HttpHeaders.Host to listOf("bee.tractat.us"), HttpHeaders.Origin to listOf("https://bee.tractat.us"))
                assertNull(parsed.siteRule().refusal("/", headers, behindTheProxy))
            },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--lan"), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/?k=fake"), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url"), home, NO_ENV) } },
        )
    }

    /** A public host has an owner, or doesn't start: nobody there is on loopback to own it. */
    @Test
    fun aPublicHostRefusesToStartWithoutAGoodOwnerKey() {
        fun refusal(owner: String?): String? = runCatching {
            HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/"), home) { name -> owner.takeIf { name == OwnerKey.ENV } }
        }.exceptionOrNull()?.let { assertIs<IllegalArgumentException>(it).message }
        val short = FAKE_OWNER_KEY.take(OwnerKey.MIN_LENGTH - 1)
        val notBase64url = FAKE_OWNER_KEY.dropLast(1) + "+"
        assertAll(
            { assertNull(refusal(FAKE_OWNER_KEY), "rig: a good key starts it") },
            { assertContains(refusal(null).orEmpty(), OwnerKey.ENV, message = "none set") },
            { assertContains(refusal("").orEmpty(), OwnerKey.ENV, message = "set, empty") },
            { assertContains(refusal(short).orEmpty(), OwnerKey.ENV, message = "too short") },
            { assertContains(refusal(notBase64url).orEmpty(), OwnerKey.ENV, message = "not base64url") },
            { assertFalse(short in refusal(short).orEmpty(), "the message never holds the key") },
            { assertEquals(Reach.Loopback, HostConfig.fromArgs(emptyList(), home, OWNER_ENV).reach, "a key alone makes no public host") },
        )
    }

    @Test
    fun clientIpHeaderNamesTheHeaderAPublicHostsProxySets() {
        val parsed = HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--client-ip-header", "Fly-Client-IP"), home, OWNER_ENV)
        assertAll(
            { assertEquals(ForwardedPeer.ClientHeader("Fly-Client-IP"), parsed.reach.peers()) },
            { assertFailsWith<IllegalArgumentException>("not without --public-url") { HostConfig.fromArgs(listOf("--client-ip-header", "Fly-Client-IP"), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException>("nor on a LAN host") { HostConfig.fromArgs(listOf("--lan", "--client-ip-header", "Fly-Client-IP"), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException>("no header name") { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--client-ip-header", "Fly Client IP"), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException>("an empty one") { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--client-ip-header", ""), home, OWNER_ENV) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--public-url", "https://bee.tractat.us/", "--client-ip-header"), home, OWNER_ENV) } },
        )
    }

    @Test
    fun hostNameAddsANameTheHostAnswersTo() {
        val named = HostConfig.fromArgs(listOf("--host-name", "bee-lan.test"), home, NO_ENV)
        val local = Arrival("127.0.0.1", HostConfig.DEFAULT_PORT)
        assertAll(
            { assertEquals(setOf("bee-lan.test"), named.hostNames) },
            { assertEquals(emptySet(), HostConfig.fromArgs(emptyList(), home, NO_ENV).hostNames, "none by default") },
            { assertNull(named.siteRule().refusal("/", headersOf(HttpHeaders.Host, "bee-lan.test:8233"), local)) },
            { assertNotNull(HostConfig(dataDir = home).siteRule().refusal("/", headersOf(HttpHeaders.Host, "bee-lan.test:8233"), local), "not without the flag") },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--host-name", "a", "--host-name", "b"), home, NO_ENV) } },
        )
    }

    @Test
    fun newLinkAndForgetDevicesSetTheKeyReset() {
        fun reset(vararg args: String) = HostConfig.fromArgs(args.toList(), home, NO_ENV).keyReset
        assertAll(
            { assertEquals(KeyReset.Keep, reset("--lan"), "keep every key by default") },
            { assertEquals(KeyReset.NewLink, reset("--new-link", "--lan")) },
            { assertEquals(KeyReset.ForgetDevices, reset("--forget-devices")) },
            { assertEquals(KeyReset.ForgetDevices, reset("--new-link", "--forget-devices"), "forgetting mints a new link anyway") },
            { assertEquals(9000, HostConfig.fromArgs(listOf("--new-link", "--port", "9000"), home, NO_ENV).port, "a switch takes no value") },
            { assertFailsWith<IllegalArgumentException> { reset("--new-link", "--new-link") } },
        )
    }

    @Test
    fun joinRequestsPerHourSetsThePairingBudget() {
        assertAll(
            { assertEquals(PairingBudget(5), HostConfig.fromArgs(listOf("--join-requests-per-hour", "5"), home, NO_ENV).pairingBudget) },
            { assertEquals(PairingBudget(30), HostConfig.fromArgs(emptyList(), home, NO_ENV).pairingBudget, "30 by default") },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--join-requests-per-hour", "0"), home, NO_ENV) } },
            { assertFailsWith<IllegalArgumentException> { HostConfig.fromArgs(listOf("--join-requests-per-hour", "many"), home, NO_ENV) } },
        )
    }

    private companion object {
        val NO_ENV: (String) -> String? = { null }
        val OWNER_ENV: (String) -> String? = { name -> FAKE_OWNER_KEY.takeIf { name == OwnerKey.ENV } }
    }
}
