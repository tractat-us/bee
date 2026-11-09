package us.tractat.bee.host

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** `--public-url`'s reading ([PublicUrl.parse]) and the names a browser gives it. */
class PublicUrlTest {
    @Test
    fun anHttpsOriginAtItsPageParses() {
        val url = PublicUrl.parse("https://Bee.Tractat.us/")
        assertAll(
            { assertEquals("bee.tractat.us", url.authority, "no port: https's default") },
            { assertEquals("https://bee.tractat.us", url.origin) },
            { assertEquals("https://bee.tractat.us/", url.page) },
            { assertEquals(443, url.port) },
            { assertEquals(url, PublicUrl.parse("https://bee.tractat.us"), "no path is the same page") },
        )
    }

    @Test
    fun aTestProxyOverHttpKeepsItsPort() {
        val url = PublicUrl.parse("http://localhost:9000/")
        assertAll(
            { assertEquals("localhost:9000", url.authority) },
            { assertEquals("http://localhost:9000", url.origin) },
            { assertEquals("bee.example", PublicUrl.parse("http://bee.example:80/").authority, "http's default, spelled out") },
            { assertEquals("bee.example:443", PublicUrl.parse("http://bee.example:443/").authority, "443 is https's default, not http's") },
        )
    }

    @Test
    fun anythingButASchemeHostAndPageIsRefused() {
        val refused = listOf(
            "bee.tractat.us",
            "ws://bee.tractat.us/",
            "ftp://bee.tractat.us/",
            "https:///",
            "https://iain@bee.tractat.us/",
            "https://bee.tractat.us/board",
            "https://bee.tractat.us/?k=fake",
            "https://bee.tractat.us/#k=fake",
            "https://bee tractat.us/",
            "",
        )
        val parsed = refused.filter { text -> runCatching { PublicUrl.parse(text) }.exceptionOrNull() !is IllegalArgumentException }
        assertEquals(emptyList(), parsed, "each of these parses, or fails otherwise")
    }
}
