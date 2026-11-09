package us.tractat.bee.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingWire
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A public host with no `--client-ip-header`, `--public-url https://bee.tractat.us/` alone
 * (bee#145), on a real socket ([Handshakes]): behind a proxy that appends the client it saw to
 * `X-Forwarded-For`, so each call's caller is that header's rightmost entry
 * ([ForwardedPeer.AppendedForwardedFor]). Fly's proxy is not one; [PublicHostTest] is the host
 * as the `Dockerfile` starts it there.
 */
class AppendingProxyHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w1-appending-proxy-")
    private val config = HostConfig.fromArgs(listOf("--public-url", PUBLIC_URL, "--port", "0", "--data-dir", dataDir.toString()), home = Path.of("/nonexistent"))
    private val host = runBlocking { BeeHost.start(scope, config) }
    private val calls = Handshakes(host.port)
    private val board = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"

    @AfterTest
    fun stop() {
        host.close()
        scope.cancel()
        dataDir.toFile().deleteRecursively()
    }

    /** Each dial's TCP peer is loopback, which the last case shows is local without a forwarding header. */
    @Test
    fun aForwardedCallIsNeverLocal() {
        assertAll(
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "X-Forwarded-For: 127.0.0.1"), "a forwarded loopback address") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "X-Forwarded-For: 203.0.113.7, 127.0.0.1"), "loopback, rightmost") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "Forwarded: for=127.0.0.1"), "RFC 7239's header") },
            { assertEquals(FORBIDDEN, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN, "X-Forwarded-For: "), "an empty one") },
            { assertEquals(SWITCHING, calls.upgrade(board, PUBLIC_HOST, PUBLIC_ORIGIN), "rig: the same dial with no forwarding header is local") },
        )
    }

    /**
     * Pairing limits each client by the address the proxy appended, the rightmost `X-Forwarded-For`
     * entry. Fly's shape read this way names the app's address for every client, which is why the
     * hosted board passes `--client-ip-header Fly-Client-IP`.
     */
    @Test
    fun pairingLimitsTheAddressTheProxyAppended() {
        calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 198.51.100.1, 203.0.113.7").use { first ->
            val opened = first.message()
            val sameClient = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 203.0.113.7").use { it.message() }
            val anotherClient = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 203.0.113.7, 198.51.100.9").use { it.message() }
            val flyShaped = calls.events(PAIR, PUBLIC_HOST, SAME_ORIGIN, "X-Forwarded-For: 198.51.100.5, 203.0.113.7", "Fly-Client-IP: 198.51.100.5").use { it.message() }
            assertAll(
                { assertIs<PairingMessage.Code>(opened) },
                { assertEquals(PairingMessage.Ended(PairingEnd.TooSoon), sameClient, "one client, whatever it put left of the proxy's entry") },
                { assertIs<PairingMessage.Code>(anotherClient, "another client, from the same proxy") },
                { assertEquals(PairingMessage.Ended(PairingEnd.TooSoon), flyShaped, "Fly-Client-IP unread without the flag") },
            )
        }
    }

    private fun Handshakes.Events.message(): PairingMessage = Json.decodeFromString(PairingMessage.serializer(), next())

    private companion object {
        const val PUBLIC_URL = "https://bee.tractat.us/"
        const val PUBLIC_HOST = "bee.tractat.us"
        const val PUBLIC_ORIGIN = "https://bee.tractat.us"
        const val PAIR = "${PairingWire.PATH}?${PairingWire.SCREEN_PARAM}=probe"
        const val SAME_ORIGIN = "Sec-Fetch-Site: same-origin"
    }
}
