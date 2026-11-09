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
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import us.tractat.bee.core.InviteEnd
import us.tractat.bee.core.InviteLinks
import us.tractat.bee.core.InviteWire
import us.tractat.bee.core.MintedInvite
import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.Redeemed
import us.tractat.bee.core.Redemption
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
     * A host behind a proxy ([Reach.Public]), at its public [url]. Its join link's key admits no
     * one ([JoinLink.Off]); each invite is minted instead, from [invites]: a link that admits
     * whoever opens it, for a day (#215). Every admitted viewer may mint one.
     */
    class Public(val url: PublicUrl, val invites: Invites) : Invitation
}

/** The longest redemption body: a 43-byte secret, a name of at most 40 characters in UTF-8, and JSON's keys. */
private const val MAX_REDEMPTION_BYTES = 512

/** The longest mint body: the tab's invite's 43-byte secret, or nothing. */
private const val MAX_KEPT_BYTES = 64

/**
 * The host's invites at [InviteLinks.PATH].
 *
 * `GET` gives a page the join link to show as a QR code, or says the page may mint one. On an
 * [Invitation.Open] host it answers the owner alone, a [Caller.local] caller with no key, read as
 * the socket gate reads it ([peers]), with a 403 to anyone else: another device holds a key
 * already, and doesn't pass it on. On an [Invitation.Public] host it answers any caller whose
 * `?k=` the host admits ([Pairing.admits]) with the bare public URL and [InviteLinks.Joins.Anywhere],
 * which tells the page to mint its link. A host that is [Invitation.Closed] answers 404, and the
 * page offers no invite.
 *
 * An [Invitation.Open] link names the port the call arrived on ([arrivals]): the port the host is
 * bound to, and holds [Pairing.joinKey]'s key, which goes into the response only, never a log.
 *
 * On an [Invitation.Public] host only (others answer 404):
 *
 * - `POST` [InviteLinks.PATH], with an admitted `?k=`, mints an invite ([Invites.mint]) and answers
 *   its [MintedInvite]: the public URL with the secret in its fragment, `#i=<secret>`. That answer
 *   is the only place the secret ever appears. A body holding the tab's invite's secret, the
 *   only other place it travels, asks for that invite back if it still works ([Invites.keepOrMint]),
 *   so a tab never shows a dead link. The invite records its minter by the key's hash;
 *   past [Invites.MAX_LIVE_PER_MINTER] live, or [Invites.MAX_LIVE] on the board, it answers 429.
 * - `POST` [InviteWire.REDEEM_PATH], with a [Redemption] as its body (at most [MAX_REDEMPTION_BYTES],
 *   read no further, with or without a length), needs no key: the secret is the capability. It
 *   answers a [Redeemed]: a device key, or why not, with a status a proxy's own page can't be
 *   mistaken for; 503 when the host couldn't record the use, and so gave no key.
 */
fun Route.inviteEndpoint(invitation: Invitation, pairing: Pairing, peers: PeerAddress, arrivals: ArrivalAddress) {
    post(InviteLinks.PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (invitation !is Invitation.Public) return@post call.respond(HttpStatusCode.NotFound, "Only a hosted board mints invites: this one's join link is at GET.")
        val key = keyOf(call)?.takeIf(pairing::admits)
            ?: return@post call.respond(HttpStatusCode.Forbidden, "Only someone already on the board may invite.")
        val kept = call.receiveAtMost(MAX_KEPT_BYTES)
            ?: return@post call.respondText("A mint carries the tab's invite, or nothing.", status = HttpStatusCode.PayloadTooLarge)
        // The minter is kept as its key's hash, never the key.
        val by = KeyHash.of(key)
        val outcome = if (kept.isEmpty()) invitation.invites.mint(by) else invitation.invites.keepOrMint(by, InviteSecret(kept))
        when (val minted = outcome) {
            is Mint.Minted -> {
                val link = "${invitation.url.page}#${InviteWire.SECRET_PARAM}=${minted.secret.value}"
                call.respondText(Json.encodeToString(MintedInvite.serializer(), MintedInvite(link, minted.expiresAt)), ContentType.Application.Json)
            }
            Mint.BoardFull -> call.respondText("The board has as many live invites as it keeps. The oldest lapses within a day.", status = HttpStatusCode.TooManyRequests)
            Mint.MinterFull -> call.respondText("You have ${Invites.MAX_LIVE_PER_MINTER} live invites already. Each lapses a day after it was made.", status = HttpStatusCode.TooManyRequests)
        }
    }
    post(InviteWire.REDEEM_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (invitation !is Invitation.Public) return@post call.respond(HttpStatusCode.NotFound, "Only a hosted board has invites.")
        val body = call.receiveAtMost(MAX_REDEMPTION_BYTES)
            ?: return@post call.respondText("A redemption is a secret and a name, and nothing more.", status = HttpStatusCode.PayloadTooLarge)
        val redemption = try {
            Json.decodeFromString(Redemption.serializer(), body)
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException is an IllegalArgumentException.
            return@post call.respondText("A redemption is JSON: an invite and a name.", status = HttpStatusCode.BadRequest)
        }
        val redeemed = invitation.invites.redeem(redemption.invite, redemption.name)
        call.respondText(Json.encodeToString(Redeemed.serializer(), redeemed), ContentType.Application.Json, statusOf(redeemed))
    }
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
            is Invitation.Public -> call.respondLinks(pairing.admits(keyOf(call)), "Only someone already on the board may ask for its link.") {
                InviteLinks(numeric = emptyList(), named = invitation.url.page, joins = InviteLinks.Joins.Anywhere)
            }
        }
    }
}

private fun statusOf(redeemed: Redeemed): HttpStatusCode = when (redeemed) {
    is Redeemed.Key -> HttpStatusCode.OK
    is Redeemed.Refused -> when (redeemed.reason) {
        InviteEnd.Unknown -> HttpStatusCode.NotFound
        InviteEnd.Lapsed, InviteEnd.Used -> HttpStatusCode.Gone
        InviteEnd.DailyLimit -> HttpStatusCode.TooManyRequests
        InviteEnd.Unavailable -> HttpStatusCode.ServiceUnavailable
        InviteEnd.BadName -> HttpStatusCode.BadRequest
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
