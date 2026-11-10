package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.PairingWire
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.OK
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The host refuses a call from another site, or one that names it by a name it doesn't go by
 * (bee#45), on a real host bound to loopback, dialled over a raw socket ([Handshakes]) so each
 * test writes the `Host` and `Origin` headers a browser would.
 *
 * Every dial here comes from 127.0.0.1, which the key gate lets through, and names the host's
 * board, so a 403 is this gate's and a 101 or a 200 is a call it admitted. The LAN cases, where
 * the host is reached by its LAN name or address, are [LanKeyGateTest]'s: a test can't bind the
 * laptop to the network.
 */
class SiteGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w45-site-gate-")
    private val host = runBlocking { BeeHost.start(scope, HostConfig(port = 0, dataDir = dataDir)) }
    private val calls = Handshakes(host.port)
    private val own = "127.0.0.1:${host.port}"

    // kuilt's server looms read the dialler's fabric id from `peer`.
    private val board = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"
    private val claims = "${BeeHost.CLAIMS_PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"

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
            { assertEquals(FORBIDDEN, calls.upgrade(board, own, origin = "http://evil.example"), "another site") },
            { assertEquals(FORBIDDEN, calls.upgrade(claims, own, origin = "http://evil.example"), "another site, on claims") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, own, origin = "http://127.0.0.1:$otherPort"), "a page on another loopback port") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, own, origin = "https://127.0.0.1:${host.port}"), "another scheme") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, own, origin = "null"), "an opaque origin: a sandboxed frame or a file") },
        )
    }

    /** DNS rebinding: a name the attacker owns, resolving to this machine. */
    @Test
    fun aRebindingHostNameIsRefusedEverywhere() {
        val evil = "evil.example:${host.port}"
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board, evil, origin = "http://$evil"), "the attack: the page's own origin is the rebound name") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, evil)) },
            { assertEquals(FORBIDDEN, calls.upgrade(claims, evil)) },
            { assertEquals(FORBIDDEN, calls.get(BoardId.PATH, evil)) },
            { assertEquals(FORBIDDEN, calls.get(PairingWire.PATH, evil)) },
            { assertEquals(FORBIDDEN, calls.upgrade(board, "127.0.0.1:${host.port + 1}"), "the right address, another port") },
        )
    }

    /** The JVM bridge sends no `Origin`. */
    @Test
    fun theBridgeWithNoOriginIsAdmitted() {
        assertAll(
            { assertEquals(SWITCHING, calls.upgrade(board, own)) },
            { assertEquals(SWITCHING, calls.upgrade(claims, own)) },
        )
    }

    @Test
    fun theBoardPageFromItsOwnOriginIsAdmittedOnLoopback() {
        val named = "localhost:${host.port}"
        assertAll(
            { assertEquals(SWITCHING, calls.upgrade(board, own, origin = "http://$own")) },
            { assertEquals(SWITCHING, calls.upgrade(claims, own, origin = "http://$own")) },
            { assertEquals(SWITCHING, calls.upgrade(board, named, origin = "http://$named"), "the page opened as localhost") },
            { assertEquals(OK, calls.get(BoardId.PATH, own)) },
            { assertEquals(OK, calls.get(BoardId.PATH, named)) },
        )
    }

    /**
     * A page on another site that loads `/pair` as an `<img>`, a `<script>` or a no-cors `fetch`
     * sends no `Origin`, only Fetch Metadata (bee#54). Admitted, it would hold a pairing slot and
     * post its `?screen=` text to the board as a join request. The gate refuses each before any
     * route runs, so a `GET`, which `/pair` has no route for, gets its 403 as a `POST` does.
     */
    @Test
    fun aSubresourceFromAnotherSiteIsRefusedByItsFetchMetadata() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "an <img> on another site") },
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, SAME_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image"), "an <img> on another loopback port") },
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: empty"), "a no-cors fetch") },
            { assertEquals(FORBIDDEN, calls.post(PAIR, own, "", CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: empty").first, "a no-cors POST, which opens a pairing") },
            { assertEquals(FORBIDDEN, calls.get("/", own, CROSS_SITE, "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: iframe"), "the board page framed by another site") },
            { assertEquals(FORBIDDEN, calls.get(BoardId.PATH, own, CROSS_SITE, "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty")) },
        )
    }

    /**
     * No legitimate caller navigates to `/pair`: the page `POST`s to it, and a join link opens the
     * page. Chromium marks a speculation-rules prefetch from another site's page
     * `none` with `Sec-Purpose: prefetch`, and another site setting `location.href` is a
     * `cross-site` navigation, so `/pair` takes neither (bee#55's review).
     */
    @Test
    fun aNavigationOrAPrefetchOfThePairingChannelIsRefused() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, NONE, NAVIGATE, DOCUMENT, "Sec-Purpose: prefetch"), "another site's speculation rules") },
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, CROSS_SITE, NAVIGATE, DOCUMENT), "another site setting location.href") },
            { assertEquals(FORBIDDEN, calls.get(PAIR, own, NONE, NAVIGATE, DOCUMENT), "a typed URL") },
        )
    }

    /** A join link opened from chat navigates to the page, and the page's own calls are same-origin. */
    @Test
    fun aNavigationToThePageTheBoardsOwnCallsAndNoFetchMetadataAreAdmitted() {
        assertAll(
            { assertEquals(OK, calls.get("/", own, CROSS_SITE, NAVIGATE, DOCUMENT), "the board page, from a link") },
            { assertEquals(OK, calls.get("/", own, NONE, NAVIGATE, DOCUMENT), "the board page, typed or bookmarked") },
            { assertEquals(OK, calls.post(PAIR, own, "", "Sec-Fetch-Site: same-origin", "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty").first, "the board page's own pairing") },
            { assertEquals(OK, calls.post(PAIR, own, "").first, "no Fetch Metadata: an older browser") },
        )
    }

    /**
     * The join link goes to the owner's own page alone: a page from another site, open in a browser
     * on this machine, is a loopback caller too, so only the site gate stands between it and the
     * key. A host on loopback alone has no link, so this one is on `--lan`.
     */
    @Test
    fun anotherSitesPageOnThisMachineIsRefusedTheJoinLink() {
        val lanDir = Files.createTempDirectory("w45-site-gate-lan-")
        val lan = runBlocking { BeeHost.start(scope, HostConfig(port = 0, reach = Reach.Lan, dataDir = lanDir)) }
        try {
            val lanCalls = Handshakes(lan.port)
            val at = "127.0.0.1:${lan.port}"
            assertAll(
                { assertEquals(OK, lanCalls.get(InviteLinks.PATH, at, "Sec-Fetch-Site: same-origin", "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty"), "the owner's own page") },
                { assertEquals(FORBIDDEN, lanCalls.get(InviteLinks.PATH, at, "Origin: http://evil.example"), "another site, by its Origin") },
                { assertEquals(FORBIDDEN, lanCalls.get(InviteLinks.PATH, at, CROSS_SITE, "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: empty"), "another site, by its Fetch Metadata") },
            )
        } finally {
            lan.close()
            lanDir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PAIR = "${PairingWire.PATH}?${PairingWire.SCREEN_PARAM}=probe"
        const val CROSS_SITE = "Sec-Fetch-Site: cross-site"
        const val SAME_SITE = "Sec-Fetch-Site: same-site"
        const val NONE = "Sec-Fetch-Site: none"
        const val NAVIGATE = "Sec-Fetch-Mode: navigate"
        const val DOCUMENT = "Sec-Fetch-Dest: document"
    }
}
