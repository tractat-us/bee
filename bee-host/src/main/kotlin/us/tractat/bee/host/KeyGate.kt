package us.tractat.bee.host

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import us.tractat.bee.core.PairingWire
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.websocket.KtorConnectionSource
import us.tractat.kuilt.websocket.KtorRoomHost
import java.net.InetAddress

/** The address a call came from, as an IP literal. */
fun interface PeerAddress {
    fun of(call: ApplicationCall): String

    companion object {
        /**
         * The TCP peer, never a forwarded header: no proxy stands in front of the host, and a
         * header is whatever the dialler says it is.
         */
        val DIRECT: PeerAddress = PeerAddress { it.request.local.remoteAddress }
    }
}

/**
 * Whether [literal] is a loopback IP address. A host name is never loopback here, and nor is
 * anything unparseable: resolving a name would cost a lookup, and a name can say anything.
 */
fun isLoopback(literal: String): Boolean = try {
    InetAddress.ofLiteral(literal).isLoopbackAddress
} catch (_: IllegalArgumentException) {
    false
}

/** The host's two key-gated endpoints, mounted by [mountEndpoints]. */
class HostEndpoints(val board: KtorConnectionSource, val claims: KtorRoomHost)

/**
 * Mounts every route the host serves (spec §5.4, §5.7):
 *
 * - the board relay at [BoardRelay.PATH] and the claims voter at [BeeHost.CLAIMS_PATH], **behind
 *   the key gate**: a call from a loopback address passes, and any other passes only with a key
 *   [Pairing] minted in its `?k=`. Anything else gets a 403 before the WebSocket upgrade, so it
 *   never reaches the hub or the voter.
 * - the pairing socket at [PairingWire.PATH] and the page at `/`, open to all: they are what a
 *   keyless device needs, and neither carries board state, presence or claims.
 *
 * The gate is installed on the route nodes kuilt's own `webSocket(path)` created, so it applies to
 * whatever request Ktor routes there, however its path was spelled. It is on whatever [Reach] the
 * host binds; on [Reach.Loopback] no call can fail it.
 *
 * @param peers where each call came from; production passes [PeerAddress.DIRECT]
 * @param desk serves the pairing socket, over [pairing]
 */
fun Application.mountEndpoints(pairing: Pairing, peers: PeerAddress, desk: PairingDesk): HostEndpoints {
    val board = KtorConnectionSource(this, BoardRelay.PATH)
    val claims = KtorRoomHost(this, BeeHost.CLAIMS_PATH, BeeHost.CLAIMS_SERVER, Pattern(BeeHost.CLAIMS_SESSION))
    val admits = { call: ApplicationCall ->
        isLoopback(peers.of(call)) || pairing.admits(call.request.queryParameters[PairingWire.KEY_PARAM])
    }
    routing {
        for (gated in listOf(BoardRelay.PATH, BeeHost.CLAIMS_PATH)) {
            route(gated) { install(KeyGate) { this.admits = admits } }
        }
        pairingEndpoint(desk, peers)
        staticResources("/", "web")
    }
    return HostEndpoints(board, claims)
}

/** [KeyGate]'s rule. The default refuses every call, so a gate installed unconfigured fails closed. */
class KeyGateConfig {
    var admits: (ApplicationCall) -> Boolean = { false }
}

/** Refuses, with a 403, every call [KeyGateConfig.admits] doesn't pass, before the route's handler runs. */
private val KeyGate = createRouteScopedPlugin("BeeKeyGate", ::KeyGateConfig) {
    val admits = pluginConfig.admits
    onCall { call ->
        if (!admits(call)) {
            call.respond(HttpStatusCode.Forbidden, "This device needs a key: open the page to pair it.")
        }
    }
}
