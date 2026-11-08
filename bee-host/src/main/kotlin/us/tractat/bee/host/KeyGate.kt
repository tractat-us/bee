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
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import us.tractat.bee.core.PairingWire
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.websocket.KtorConnectionSource
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.KtorServerLoom
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

/**
 * The largest WebSocket frame the host reads, in bytes. A frame whose header announces more closes
 * its socket with 1009 (too big) before Ktor allocates a buffer for it; Ktor's own default is
 * `Long.MAX_VALUE`, so a header alone could make the host allocate up to 2 GiB.
 *
 * The largest legitimate frame is a board `FullState`, sent once per spoke connection. Nothing
 * measures one yet; a board of text surfaces and their action records is kilobytes to a few MiB,
 * so 64 MiB leaves an order of magnitude of headroom. Only a loopback or keyed device reaches a
 * WebSocket at all ([mountEndpoints]), so this is defence in depth, not the key gate.
 */
const val MAX_FRAME_BYTES: Long = 64L * 1024 * 1024

/** The host's two key-gated endpoints, mounted by [mountEndpoints]. */
class HostEndpoints(val board: KtorConnectionSource, val claims: KtorRoomHost)

/**
 * Mounts every route the host serves (spec §5.4, §5.7):
 *
 * - the board relay at [BoardRelay.PATH] and the claims voter at [BeeHost.CLAIMS_PATH], **behind
 *   the key gate**: a call from a loopback address passes, and any other passes only with a key
 *   [Pairing] minted in its `?k=`. Anything else gets a 403 before the WebSocket upgrade, so it
 *   never reaches the hub or the voter.
 * - the pairing channel at [PairingWire.PATH] and the page at `/`, open to all: they are what a
 *   keyless device needs, and neither carries board state, presence or claims.
 *
 * The gate is installed on the route nodes kuilt's own `webSocket(path)` created, so it applies to
 * whatever request Ktor routes there, however its path was spelled. It is on whatever [Reach] the
 * host binds; on [Reach.Loopback] no call can fail it.
 *
 * @param peers where each call came from; production passes [PeerAddress.DIRECT]
 * @param desk serves the pairing channel, over [pairing]
 */
fun Application.mountEndpoints(pairing: Pairing, peers: PeerAddress, desk: PairingDesk): HostEndpoints {
    installBoundedWebSockets()
    install(SSE)
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

/**
 * Installs Ktor's [WebSockets] with a [MAX_FRAME_BYTES] bound, before kuilt's endpoints would
 * install it unbounded. kuilt skips its own install when the plugin is already there, so this sets
 * kuilt's ping too: [KtorServerLoom.DEFAULT_PING_PERIOD] as both period and pong timeout, exactly
 * what kuilt installs, or a half-open link would never be torn down. Workaround for kuilt#3145,
 * which asks kuilt's install to take a frame bound.
 */
private fun Application.installBoundedWebSockets() {
    install(WebSockets) {
        pingPeriod = KtorServerLoom.DEFAULT_PING_PERIOD
        timeout = KtorServerLoom.DEFAULT_PING_PERIOD
        maxFrameSize = MAX_FRAME_BYTES
    }
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
