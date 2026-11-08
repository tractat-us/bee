package us.tractat.bee.host

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [SiteRule]'s reading of `Host`, `Origin` (bee#45) and, by path, Fetch Metadata (bee#54). Its place in front of every route is [SiteGateTest]'s. */
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
    fun aCallFromAnotherSiteIsRefusedUnlessItNavigatesToThePage() {
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

    /** A join link or a typed URL opens the page; the page's own calls are `same-origin`. */
    @Test
    fun thePageTakesANavigationFromAnywhere() {
        val own = "127.0.0.1:8233"
        assertAll(
            { assertNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT), "a join link from chat") },
            { assertNull(rule.refusal(own, null, loopback, SAME_SITE, NAVIGATE, DOCUMENT)) },
            { assertNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = "/index.html"), "the page by its file name") },
            { assertNull(rule.refusal(own, null, loopback, NONE, NAVIGATE, DOCUMENT), "a typed URL") },
            { assertNull(rule.refusal(own, null, loopback, SAME_ORIGIN, NAVIGATE, DOCUMENT), "a reload") },
            { assertNotNull(rule.refusal(own, null, loopback, NONE, NAVIGATE, DOCUMENT, PREFETCH), "the browser's own prefetch of a typed URL: its subresources would be refused, so the board would activate blank") },
            { assertNull(rule.refusal(own, null, loopback), "no Fetch Metadata: an older browser") },
        )
    }

    /**
     * Every other path takes only the page's own calls, which are `same-origin`, and the bridge's,
     * which carry no Fetch Metadata. No legitimate caller navigates to `/pair`, and Chromium marks
     * a speculation-rules prefetch from another site's page `none` (bee#55's review).
     */
    @Test
    fun everyOtherPathTakesOnlyThePagesOwnCallsAndTheBridge() {
        val own = "127.0.0.1:8233"
        assertAll(
            { assertNotNull(rule.refusal(own, null, loopback, NONE, NAVIGATE, DOCUMENT, PREFETCH, path = PAIR), "another site's speculation rules") },
            { assertNotNull(rule.refusal(own, null, loopback, NONE, NAVIGATE, DOCUMENT, path = PAIR), "a typed URL") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = PAIR), "another site setting location.href") },
            { assertNotNull(rule.refusal(own, null, loopback, SAME_SITE, NAVIGATE, DOCUMENT, path = PAIR), "a page on another loopback port") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = "/board-id")) },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = "/bee.js"), "an asset of the page") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = "/%70air"), "/pair, percent-encoded") },
            { assertNotNull(rule.refusal(own, null, loopback, CROSS_SITE, NAVIGATE, DOCUMENT, path = "/index.html/"), "not the page's own spelling") },
            { assertNotNull(rule.refusal(own, null, loopback, SAME_ORIGIN, CORS, EMPTY, PREFETCH, path = PAIR), "a prefetch, even a same-origin one") },
            { assertNotNull(rule.refusal(own, null, loopback, PREFETCH, path = PAIR), "a prefetch with no Sec-Fetch-Site") },
            { assertNotNull(rule.refusal(own, null, loopback, "Sec-Purpose: prefetch;prerender", path = PAIR), "a prerender") },
            { assertNull(rule.refusal(own, "http://$own", loopback, SAME_ORIGIN, CORS, EMPTY, path = PAIR), "the page's own channel") },
            { assertNull(rule.refusal(own, null, loopback, SAME_ORIGIN, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: script", path = "/bee.js"), "the page's own script") },
            { assertNull(rule.refusal(own, null, loopback, path = PAIR), "no Fetch Metadata: the bridge, or an older browser") },
            { assertNull(rule.refusal(own, null, loopback, "Sec-Fetch-Mode: no-cors", path = PAIR), "a mode, but no Sec-Fetch-Site") },
        )
    }

    private companion object {
        const val PAIR = "/pair"
        const val CROSS_SITE = "Sec-Fetch-Site: cross-site"
        const val SAME_SITE = "Sec-Fetch-Site: same-site"
        const val SAME_ORIGIN = "Sec-Fetch-Site: same-origin"
        const val NONE = "Sec-Fetch-Site: none"
        const val NAVIGATE = "Sec-Fetch-Mode: navigate"
        const val CORS = "Sec-Fetch-Mode: cors"
        const val DOCUMENT = "Sec-Fetch-Dest: document"
        const val EMPTY = "Sec-Fetch-Dest: empty"
        const val PREFETCH = "Sec-Purpose: prefetch"
    }
}

/** [SiteRule.refusal] of a call to [path], with [host], [origin] and these other [headers], each `name: value`. */
private fun SiteRule.refusal(host: String?, origin: String?, arrival: Arrival, vararg headers: String, path: String = "/"): String? =
    refusal(path, callHeaders(host, origin, headers.toList()), arrival)

private fun callHeaders(host: String?, origin: String?, others: List<String>): Headers = headers {
    host?.let { append(HttpHeaders.Host, it) }
    origin?.let { append(HttpHeaders.Origin, it) }
    others.forEach { append(it.substringBefore(": "), it.substringAfter(": ")) }
}
