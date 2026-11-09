package us.tractat.bee.host

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.PairingWire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Whether the host has a join link to give its owner's page ([inviteEndpoint]), by its [Reach]. */
sealed interface Invitation {
    /** A host on loopback alone: no device off this machine can reach it, so it has no link to give. */
    data object Closed : Invitation

    /**
     * A host on the network: its join link at its `.local` [name], and at each address [addresses]
     * names when asked. They are read per call, since a laptop changes networks under a running host.
     */
    class Open(val name: String, val addresses: () -> List<String>) : Invitation
}

/**
 * The host's join link at [InviteLinks.PATH], for the board owner's page to show as a QR code.
 * It answers a call from loopback only, read off the TCP peer as the socket gate does ([peers],
 * never a header), with a 403 to any other: a device off this machine holds a key already, and
 * doesn't pass it on. A host that is [Invitation.Closed] answers 404, and the page offers no invite.
 *
 * Each link names the port the call arrived on ([arrivals]): the port the host is bound to. The
 * key is [Pairing.joinKey]'s, and goes into the response only, never a log.
 */
fun Route.inviteEndpoint(invitation: Invitation, pairing: Pairing, peers: PeerAddress, arrivals: ArrivalAddress) {
    get(InviteLinks.PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        when (invitation) {
            Invitation.Closed -> call.respond(HttpStatusCode.NotFound, "This host is on this machine alone: start it with --lan to invite others.")
            is Invitation.Open -> if (isLoopback(peers.of(call))) {
                val port = arrivals.of(call).port
                val key = pairing.joinKey.value
                val link = { authority: String -> "http://$authority:$port/?${PairingWire.KEY_PARAM}=$key" }
                val links = InviteLinks(numeric = invitation.addresses().map(link), named = link(invitation.name))
                call.respondText(Json.encodeToString(InviteLinks.serializer(), links), ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.Forbidden, "Only the board's owner, on the host's own machine, may ask for its join link.")
            }
        }
    }
}

/**
 * This machine's IPv4 addresses on the network, as `bin/bee-up` lists them, from each interface
 * that is up: in [lanOrder].
 */
fun lanAddresses(): List<String> =
    lanOrder(NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses().toList() })

/**
 * [addresses] as a phone in the room could open them: IPv4 only, none on loopback (127.x) or
 * link-local (169.254.x). A private address (10.x, 172.16–31.x, 192.168.x) comes first, as the
 * likeliest one the room's network shares; otherwise in the order given.
 */
internal fun lanOrder(addresses: List<InetAddress>): List<String> =
    addresses.filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        .sortedBy { !it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
