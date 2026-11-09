package us.tractat.bee.host

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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

    /**
     * A host behind a proxy ([Reach.Public]): its link is its public [url], bare. It never holds the
     * join key, which on a hosted board must not let anyone in without a code: whoever opens it
     * pairs. Nobody there is on the host's machine, so its [owner] asks with their key.
     */
    data class Public(val url: PublicUrl, val owner: OwnerKey) : Invitation
}

/**
 * The host's join link at [InviteLinks.PATH], for the board owner's page to show as a QR code. It
 * answers the owner alone, with a 403 to anyone else: another device holds a key already, and
 * doesn't pass it on. On an [Invitation.Open] host the owner is a [Caller.local] caller, read as
 * the socket gate reads it ([peers]); on an [Invitation.Public] host, the call whose `?k=` is the
 * owner key, compared in constant time. A host that is [Invitation.Closed] answers 404, and the
 * page offers no invite.
 *
 * An [Invitation.Open] link names the port the call arrived on ([arrivals]): the port the host is
 * bound to, and holds [Pairing.joinKey]'s key, which goes into the response only, never a log.
 * An [Invitation.Public] link is the public URL alone, with no key, whatever port the proxy dialled.
 */
fun Route.inviteEndpoint(invitation: Invitation, pairing: Pairing, peers: PeerAddress, arrivals: ArrivalAddress) {
    get(InviteLinks.PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val keyed = { "?${PairingWire.KEY_PARAM}=${pairing.joinKey.value}" }
        when (invitation) {
            Invitation.Closed -> call.respond(HttpStatusCode.NotFound, "This host is on this machine alone: start it with --lan to invite others.")
            // The owner's own page on a LAN host holds no key: a keyed page is a viewer's, even one
            // on this machine by the LAN name, and a viewer doesn't pass its key on.
            is Invitation.Open -> call.respondLinks(peers.of(call).local && keyOf(call) == null, "Only the board's owner, on the host's own machine, may ask for its join link.") {
                val port = arrivals.of(call).port
                val link = { authority: String -> "http://$authority:$port/${keyed()}" }
                InviteLinks(numeric = invitation.addresses().map(link), named = link(invitation.name))
            }
            is Invitation.Public -> call.respondLinks(invitation.owner.admits(keyOf(call)), "Only the board's owner may ask for its link.") {
                InviteLinks(numeric = emptyList(), named = invitation.url.page)
            }
        }
    }
}

/** The key [call] presents in its `?k=`, if any. */
private fun keyOf(call: ApplicationCall): String? = call.request.queryParameters[PairingWire.KEY_PARAM]?.takeIf { it.isNotEmpty() }

/** Answers with [links] when the caller is [owner]; otherwise a 403 saying [refusal], and no key. */
private suspend fun ApplicationCall.respondLinks(owner: Boolean, refusal: String, links: () -> InviteLinks) {
    if (owner) {
        respondText(Json.encodeToString(InviteLinks.serializer(), links()), ContentType.Application.Json)
    } else {
        respond(HttpStatusCode.Forbidden, refusal)
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
        .map { it.hostAddress }
