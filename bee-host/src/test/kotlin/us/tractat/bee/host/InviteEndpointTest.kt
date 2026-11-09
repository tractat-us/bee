package us.tractat.bee.host

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.InviteLinks
import us.tractat.kuilt.test.assertAll
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The join link at [InviteLinks.PATH], on the host's real endpoints in memory ([mountEndpoints]),
 * mounted as [LanKeyGateTest] mounts them: each call names the peer it comes from in [TEST_PEER],
 * read through an injected [PeerAddress], and arrives at loopback or at the laptop's [LAPTOP] on [PORT].
 */
class InviteEndpointTest {
    private val pairing = Pairing({ Clock.System.now() }, SecureSecrets(SecureRandom()), memoryKeys())

    private fun host(invitation: Invitation, test: suspend (HttpClient) -> Unit) = testApplication {
        application {
            val desk = PairingDesk(pairing, CompletableDeferred(), Pairing.DWELL) { Clock.System.now() }
            mountEndpoints(BoardId.mint(), pairing, SiteRule(setOf(LAN_NAME)), TEST_PEERS, TEST_ARRIVALS, desk, invitation)
        }
        test(client)
    }

    private suspend fun HttpClient.invite(peer: String): HttpResponse {
        val arrival = arrivalOf(peer)
        return get(InviteLinks.PATH) {
            header(TEST_PEER, peer)
            header(HttpHeaders.Host, "${arrival.address}:${arrival.port}")
        }
    }

    @Test
    fun aLoopbackCallerOnALanHostGetsTheJoinLinkAtEachAddressAndItsName() = host(Invitation.Open(LAN_NAME) { listOf(LAPTOP, OTHER_ADDRESS) }) { client ->
        val response = client.invite(LOOPBACK)
        val links = Json.decodeFromString(InviteLinks.serializer(), response.bodyAsText())
        val key = pairing.joinKey.value
        assertAll(
            { assertEquals(HttpStatusCode.OK, response.status) },
            { assertEquals(listOf("http://$LAPTOP:$PORT/?k=$key", "http://$OTHER_ADDRESS:$PORT/?k=$key"), links.numeric) },
            { assertEquals("http://$LAN_NAME:$PORT/?k=$key", links.named) },
            { assertEquals(links.numeric.first(), links.primary) },
            { assertTrue(pairing.admits(links.primary.substringAfter("?k=")), "the link's key is one the host admits") },
            { assertEquals("no-store", response.headers[HttpHeaders.CacheControl]) },
        )
    }

    @Test
    fun aCallerOffTheMachineIsRefusedAndGetsNoKey() = host(Invitation.Open(LAN_NAME) { listOf(LAPTOP) }) { client ->
        val response = client.invite(LAN_DEVICE)
        val body = response.bodyAsText()
        assertAll(
            { assertEquals(HttpStatusCode.Forbidden, response.status) },
            { assertFalse(pairing.joinKey.value in body) },
        )
    }

    @Test
    fun aHostOnLoopbackAloneHasNoInvite() = host(Invitation.Closed) { client ->
        assertEquals(HttpStatusCode.NotFound, client.invite(LOOPBACK).status)
    }

    @Test
    fun aHostWithNoAddressOnTheNetworkStillGivesItsNamedLink() = host(Invitation.Open(LAN_NAME) { emptyList() }) { client ->
        val links = Json.decodeFromString(InviteLinks.serializer(), client.invite(LOOPBACK).bodyAsText())
        assertAll(
            { assertEquals(emptyList(), links.numeric) },
            { assertEquals("http://$LAN_NAME:$PORT/?k=${pairing.joinKey.value}", links.primary) },
        )
    }

    @Test
    fun lanOrderKeepsIpv4OffLoopbackAndLinkLocalPrivateFirst() {
        val given = listOf("127.0.0.1", "169.254.10.2", "fe80::1", "203.0.113.9", "192.168.1.5", "::1", "10.0.0.7").map(InetAddress::ofLiteral)
        assertEquals(listOf("192.168.1.5", "10.0.0.7", "203.0.113.9"), lanOrder(given))
    }

    private companion object {
        const val TEST_PEER = "X-Bee-Test-Peer"
        const val LOOPBACK = "127.0.0.1"
        const val LAN_DEVICE = "192.168.1.20"

        /** The laptop's own LAN address, a second one it has (a VPN, say), its LAN name, and its port. */
        const val LAPTOP = "192.168.1.5"
        const val OTHER_ADDRESS = "10.8.0.2"
        const val LAN_NAME = "tractatus.local"
        const val PORT = 8233

        val TEST_PEERS = PeerAddress { call -> Caller.byPeer(requireNotNull(call.request.headers[TEST_PEER]) { "rig: no $TEST_PEER header" }) }

        fun arrivalOf(peer: String) = Arrival(if (isLoopback(peer)) LOOPBACK else LAPTOP, PORT)

        val TEST_ARRIVALS = ArrivalAddress { call -> arrivalOf(TEST_PEERS.of(call).address) }
    }
}
