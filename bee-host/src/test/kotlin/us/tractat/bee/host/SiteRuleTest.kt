package us.tractat.bee.host

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [SiteRule]'s reading of `Host` and `Origin` (bee#45). Its place in front of every route is [SiteGateTest]'s. */
class SiteRuleTest {
    private val rule = SiteRule(setOf("Tractatus.local"))
    private val loopback = Arrival("127.0.0.1", 8233)
    private val loopbackV6 = Arrival("0:0:0:0:0:0:0:1", 8233)
    private val lan = Arrival("192.168.1.5", 8233)

    @Test
    fun theHostsOwnNamesPass() {
        assertAll(
            { assertNull(rule.refusal("localhost:8233", null, loopback)) },
            { assertNull(rule.refusal("127.0.0.1:8233", null, loopback)) },
            { assertNull(rule.refusal("[::1]:8233", "http://[::1]:8233", loopbackV6), "IPv6, bracketed") },
            { assertNull(rule.refusal("tractatus.local:8233", "http://tractatus.local:8233", lan), "a name given in another case") },
            { assertNull(rule.refusal("192.168.1.5:8233", "http://192.168.1.5:8233", lan)) },
            { assertNull(rule.refusal("localhost", "http://localhost", Arrival("127.0.0.1", 80)), "the default port, left out") },
        )
    }

    @Test
    fun anyOtherNameAddressOrPortIsRefused() {
        assertAll(
            { assertNotNull(rule.refusal(null, null, loopback), "no Host at all") },
            { assertNotNull(rule.refusal("", null, loopback)) },
            { assertNotNull(rule.refusal("evil.example:8233", null, loopback)) },
            { assertNotNull(rule.refusal("localhost.evil.example:8233", null, loopback)) },
            { assertNotNull(rule.refusal("127.0.0.1:8234", null, loopback), "another port") },
            { assertNotNull(rule.refusal("localhost", null, loopback), "no port is port 80") },
            { assertNotNull(rule.refusal("192.168.1.5:8233", null, loopback), "an address the call didn't arrive at") },
            { assertNotNull(rule.refusal("tractatus.local:8233", null, Arrival("192.168.1.5", 9000))) },
            { assertNotNull(rule.refusal("::1:8233", null, loopbackV6), "IPv6 without brackets") },
            { assertNotNull(rule.refusal("localhost:8233:1", null, loopback)) },
            { assertNotNull(rule.refusal("localhost:0", null, Arrival("127.0.0.1", 0))) },
            { assertNotNull(SiteRule(emptySet()).refusal("tractatus.local:8233", null, lan),"a name not given") },
        )
    }

    @Test
    fun anOriginOtherThanTheHostsOwnIsRefused() {
        assertAll(
            { assertNotNull(rule.refusal("127.0.0.1:8233", "http://evil.example", loopback)) },
            { assertNotNull(rule.refusal("127.0.0.1:8233", "http://localhost:8233", loopback), "this machine, by another name") },
            { assertNotNull(rule.refusal("127.0.0.1:8233", "http://127.0.0.1:8234", loopback), "another port") },
            { assertNotNull(rule.refusal("127.0.0.1:8233", "https://127.0.0.1:8233", loopback), "another scheme") },
            { assertNotNull(rule.refusal("127.0.0.1:8233", "null", loopback), "an opaque origin") },
            { assertNotNull(rule.refusal("127.0.0.1:8233", "", loopback)) },
        )
    }
}

/** [SiteRule.refusal] of a call with [host], [origin] and these other [headers], each `name: value`. */
private fun SiteRule.refusal(host: String?, origin: String?, arrival: Arrival, vararg headers: String): String? =
    refusal(callHeaders(host, origin, headers.toList()), arrival)

private fun callHeaders(host: String?, origin: String?, others: List<String>): Headers = headers {
    host?.let { append(HttpHeaders.Host, it) }
    origin?.let { append(HttpHeaders.Origin, it) }
    others.forEach { append(it.substringBefore(": "), it.substringAfter(": ")) }
}
