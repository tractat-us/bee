package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.PairingWire
import us.tractat.kuilt.test.assertAll
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The host refuses a call from another site, or one that names it by a name it doesn't go by
 * (bee#45), on a real host bound to loopback, dialled over a raw socket so each test writes the
 * `Host` and `Origin` headers a browser would.
 *
 * Every dial here comes from 127.0.0.1, which the key gate lets through, so a 403 is this gate's
 * and a 101 or a 200 is a call it admitted. The LAN cases, where the host is reached by its LAN
 * name or address, are [LanKeyGateTest]'s: a test can't bind the laptop to the network.
 */
class SiteGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w45-site-gate-")
    private val host = runBlocking { BeeHost.start(scope, HostConfig(port = 0, dataDir = dataDir)) }
    private val own = "127.0.0.1:${host.port}"

    @AfterTest
    fun stop() {
        host.close()
        scope.cancel()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun aPageFromAnotherSiteIsRefusedOnBoardAndClaims() {
        val otherPort = if (host.port == 1) 2 else host.port - 1
        assertAll(
            { assertEquals(FORBIDDEN, upgrade(BOARD, own, origin = "http://evil.example"), "another site") },
            { assertEquals(FORBIDDEN, upgrade(CLAIMS, own, origin = "http://evil.example"), "another site, on claims") },
            { assertEquals(FORBIDDEN, upgrade(BOARD, own, origin = "http://127.0.0.1:$otherPort"), "a page on another loopback port") },
            { assertEquals(FORBIDDEN, upgrade(BOARD, own, origin = "https://127.0.0.1:${host.port}"), "another scheme") },
            { assertEquals(FORBIDDEN, upgrade(BOARD, own, origin = "null"), "an opaque origin: a sandboxed frame or a file") },
        )
    }

    /** DNS rebinding: a name the attacker owns, resolving to this machine. */
    @Test
    fun aRebindingHostNameIsRefusedEverywhere() {
        val evil = "evil.example:${host.port}"
        assertAll(
            { assertEquals(FORBIDDEN, upgrade(BOARD, evil, origin = "http://$evil"), "the attack: the page's own origin is the rebound name") },
            { assertEquals(FORBIDDEN, upgrade(BOARD, evil, origin = null)) },
            { assertEquals(FORBIDDEN, upgrade(CLAIMS, evil, origin = null)) },
            { assertEquals(FORBIDDEN, get(BoardId.PATH, evil)) },
            { assertEquals(FORBIDDEN, get(PairingWire.PATH, evil)) },
            { assertEquals(FORBIDDEN, upgrade(BOARD, "127.0.0.1:${host.port + 1}", origin = null), "the right address, another port") },
        )
    }

    /** The JVM bridge sends no `Origin`. */
    @Test
    fun theBridgeWithNoOriginIsAdmitted() {
        assertAll(
            { assertEquals(SWITCHING, upgrade(BOARD, own, origin = null)) },
            { assertEquals(SWITCHING, upgrade(CLAIMS, own, origin = null)) },
        )
    }

    @Test
    fun theBoardPageFromItsOwnOriginIsAdmittedOnLoopback() {
        val named = "localhost:${host.port}"
        assertAll(
            { assertEquals(SWITCHING, upgrade(BOARD, own, origin = "http://$own")) },
            { assertEquals(SWITCHING, upgrade(CLAIMS, own, origin = "http://$own")) },
            { assertEquals(SWITCHING, upgrade(BOARD, named, origin = "http://$named"), "the page opened as localhost") },
            { assertEquals(OK, get(BoardId.PATH, own)) },
            { assertEquals(OK, get(BoardId.PATH, named)) },
        )
    }

    /**
     * A page on another site that loads `/pair` as an `<img>`, a `<script>` or a no-cors `fetch`
     * sends no `Origin`, only Fetch Metadata (bee#54). Admitted, it would hold a pairing slot open
     * and post its `?screen=` text to the board as a join request.
     */
    @Test
    fun aSubresourceFromAnotherSiteIsRefusedByItsFetchMetadata() {
        assertAll(
            { assertEquals(FORBIDDEN, fetch(PAIR, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "an <img> on another site") },
            { assertEquals(FORBIDDEN, fetch(PAIR, SAME_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "an <img> on another loopback port") },
            { assertEquals(FORBIDDEN, fetch(PAIR, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: empty"), "a no-cors fetch") },
            { assertEquals(FORBIDDEN, fetch("/", CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: iframe"), "the board page framed by another site") },
            { assertEquals(FORBIDDEN, fetch(BoardId.PATH, CROSS_SITE, "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty")) },
        )
    }

    /** A join link opened from chat is a top-level navigation from another site, and the page's own calls are same-origin. */
    @Test
    fun aNavigationTheBoardsOwnCallsAndNoFetchMetadataAreAdmitted() {
        assertAll(
            { assertEquals(OK, fetch(PAIR, CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document"), "a link from another site") },
            { assertEquals(OK, fetch("/", CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document"), "the board page, from a link") },
            { assertEquals(OK, fetch(PAIR, "Sec-Fetch-Site: same-origin", "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty"), "the board page's own channel") },
            { assertEquals(OK, fetch(PAIR, "Sec-Fetch-Site: none", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document"), "a typed URL or a bookmark") },
            { assertEquals(OK, fetch(PAIR), "no Fetch Metadata: the bridge, or an older browser") },
        )
    }

    /** The status line of a plain `GET` of [path] by the host's own address, with no `Origin` and these [headers]. */
    private fun fetch(path: String, vararg headers: String): String =
        exchange("GET $path HTTP/1.1\r\nHost: $own\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n")

    /** The status line of a WebSocket handshake for [path], with [hostHeader] and, if any, [origin]. */
    private fun upgrade(path: String, hostHeader: String, origin: String?): String {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        return exchange(
            "GET $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                (origin?.let { "Origin: $it\r\n" } ?: "") +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n",
        )
    }

    /** The status line of a plain `GET` of [path], with [hostHeader]. */
    private fun get(path: String, hostHeader: String): String =
        exchange("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n")

    private fun exchange(request: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", host.port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
        socket.getInputStream().statusCode()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val SWITCHING = "101"
        const val OK = "200"
        const val FORBIDDEN = "403"

        // kuilt's server looms read the dialler's fabric id from `peer`.
        const val BOARD = "${BoardRelay.PATH}?peer=probe"
        const val CLAIMS = "${BeeHost.CLAIMS_PATH}?peer=probe"
        const val PAIR = "${PairingWire.PATH}?${PairingWire.SCREEN_PARAM}=probe"

        const val CROSS_SITE = "Sec-Fetch-Site: cross-site"
        const val SAME_SITE = "Sec-Fetch-Site: same-site"

        /** The status code of the response's first line, `HTTP/1.1 <code> <reason>`. */
        fun InputStream.statusCode(): String {
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) line.append(read().also { check(it >= 0) { "the host hung up before a status: $line" } }.toChar())
            return line.split(' ')[1]
        }
    }
}
