package us.tractat.bee.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostAddressTest {
    @Test
    fun aWebSocketRootIsAskedOverHttpAndDialledAsGiven() {
        val host = HostAddress.parse("ws://127.0.0.1:8233")
        assertAll(
            { assertEquals("http://127.0.0.1:8233", host.http) },
            { assertEquals("ws://127.0.0.1:8233/ws/board", host.socket("/ws/board")) },
            { assertEquals("http://127.0.0.1:8233", host.toString()) },
        )
    }

    @Test
    fun everyKeylessSpellingOfOneHostIsTheSameAddress() {
        val spellings = listOf("ws://h:8233", "ws://h:8233/", "http://h:8233", "http://h:8233/", "  http://h:8233/\n", "HTTP://h:8233/")
        val parsed = spellings.map(HostAddress::parse)
        assertAll(
            { assertEquals(setOf("http://h:8233"), parsed.map { it.http }.toSet(), "http") },
            { assertEquals(setOf("ws://h:8233/ws/claims"), parsed.map { it.socket("/ws/claims") }.toSet(), "socket") },
        )
    }

    @Test
    fun aSecureSchemeStaysSecure() {
        val bySocket = HostAddress.parse("wss://bee.example:8443")
        val byPage = HostAddress.parse("https://bee.example/")
        assertAll(
            { assertEquals("https://bee.example:8443", bySocket.http) },
            { assertEquals("wss://bee.example:8443/ws/board", bySocket.socket("/ws/board")) },
            { assertEquals("https://bee.example", byPage.http) },
            { assertEquals("wss://bee.example/ws/board", byPage.socket("/ws/board")) },
        )
    }

    /** The link a `--lan` host prints, pasted as is: both sockets carry its key, and the board's id is asked without it. */
    @Test
    fun theJoinLinkCarriesItsKeyOnEverySocket() {
        val host = HostAddress.parse("http://tractatus.local:8233/?k=$KEY")
        assertAll(
            { assertEquals("http://tractatus.local:8233", host.http) },
            { assertEquals("ws://tractatus.local:8233/ws/board?k=$KEY", host.socket("/ws/board")) },
            { assertEquals("ws://tractatus.local:8233/ws/claims?k=$KEY", host.socket("/ws/claims")) },
        )
    }

    @Test
    fun aKeyThatNeedsEncodingIsEncodedOnce() {
        val host = HostAddress.parse("http://h:8233/?k=a%2Bb")
        assertEquals("ws://h:8233/ws/board?k=a%2Bb", host.socket("/ws/board"))
    }

    @Test
    fun anAddressNeverPrintsItsKey() {
        val host = HostAddress.parse("http://tractatus.local:8233/?k=$KEY")
        assertAll(
            { assertEquals("http://tractatus.local:8233, with a join key", host.toString()) },
            { assertFalse(KEY in host.toString()) },
        )
    }

    @Test
    fun whatIsNoHostIsRefused() {
        val refused = listOf(
            "127.0.0.1:8233",
            "ftp://h:8233",
            "h",
            "http://",
            "http://h:8233/board",
            "http://h:8233/#k=$KEY",
            "http://h:8233/?x=1",
            "http://h:8233/?k=",
            "http://h:8233/?k=a&k=b",
            "",
        )
        val failures = refused.map { text -> text to assertFailsWith<IllegalArgumentException>(text) { HostAddress.parse(text) } }
        assertAll(*failures.map { (text, failure) -> { assertTrue(failure.message.orEmpty().isNotBlank(), "a reason for \"$text\"") } }.toTypedArray())
    }

    @Test
    fun aRefusalNeverShowsTheKey() {
        val failures = listOf("http://h:8233/board?k=$KEY", "ftp://h:8233/?k=$KEY", "http://h:8233/?k=$KEY&x=1", "http://h:8233/?k=$KEY&k=$KEY")
            .map { assertFailsWith<IllegalArgumentException> { HostAddress.parse(it) } }
        assertAll(*failures.map { failure -> { assertFalse(KEY in failure.message.orEmpty(), failure.message) } }.toTypedArray())
    }

    private companion object {
        const val KEY = "s3cr3tJoinKey_0123456789"
    }
}
