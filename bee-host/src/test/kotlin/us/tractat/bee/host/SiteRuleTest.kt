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

    /** bee#54: a subresource or a frame from another site carries no `Origin`, only Fetch Metadata. */
    @Test
    fun aCallFromAnotherSiteIsRefusedUnlessItIsATopLevelNavigation() {
        val own = "127.0.0.1:8233"
        assertAll(
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image")) },
            { assertNotNull(rule.refusal(own, null, loopback, SAME_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: script"), "another port on this machine") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: iframe"), "a frame") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, "Sec-Fetch-Mode: navigate"), "a navigation, but not of a document") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, "Sec-Fetch-Dest: document"), "a document, but not navigated to") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE), "no mode or destination") },
            { assertNotNull(rule.refusal(own, null, loopback, "Sec-Fetch-Site: Cross-Site", "Sec-Fetch-Mode: no-cors"), "in another case") },
        )
    }

    @Test
    fun aNavigationTheHostsOwnCallsAndNoFetchMetadataPass() {
        val own = "127.0.0.1:8233"
        assertAll(
            { assertNull(rule.refusal(own, null, loopback, CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document"), "a join link from chat") },
            { assertNull(rule.refusal(own, null, loopback, SAME_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document")) },
            { assertNull(rule.refusal(own, "http://$own", loopback, "Sec-Fetch-Site: same-origin", "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty")) },
            { assertNull(rule.refusal(own, null, loopback, "Sec-Fetch-Site: none", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document"), "a typed URL") },
            { assertNull(rule.refusal(own, null, loopback, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "no Sec-Fetch-Site: the bridge, an older browser") },
        )
    }

    private companion object {
        const val CROSS_SITE = "Sec-Fetch-Site: cross-site"
        const val SAME_SITE = "Sec-Fetch-Site: same-site"
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
