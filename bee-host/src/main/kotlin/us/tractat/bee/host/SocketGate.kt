package us.tractat.bee.host

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import us.tractat.bee.core.BoardId
import us.tractat.bee.core.MAX_FRAME_BYTES
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

/** The host's two gated sockets, mounted by [mountEndpoints]. */
class HostEndpoints(val board: KtorConnectionSource, val claims: KtorRoomHost)

/**
 * Mounts every route the host serves (spec §5.4, §5.7):
 *
 * - the board relay at [BoardRelay.PATH] and the claims voter at [BeeHost.CLAIMS_PATH], **behind
 *   the socket gate**. A socket must name [board] in its `?board=` ([BoardId], bee#46), and come
 *   from a loopback address or hold a key [Pairing] minted in its `?k=`. Anything else gets a 403
 *   before the WebSocket upgrade, so it never reaches the hub or the voter, and a replica of
 *   another board never pushes its state.
 * - MCP over Streamable HTTP at [BeeHost.MCP_PATH] (served by [us.tractat.bee.mcp.HttpBridges]),
 *   behind the key half of the same gate: from loopback, or with a key [Pairing] minted in its
 *   `?k=`. It names no board, since the bridge the host runs for it dials the board itself.
 * - the board's id at [BoardId.PATH], the pairing channel at [PairingWire.PATH] and the page at
 *   `/`, open to all: they are what a keyless device needs, and none carries board state, presence
 *   or claims. The page's files, and only they, carry [BeeHost.PAGE_CSP].
 *
 * The gate is installed on the route nodes kuilt's own `webSocket(path)` created, so it applies to
 * whatever request Ktor routes there, however its path was spelled. Its key half is on whatever
 * [Reach] the host binds; on [Reach.Loopback] no call can fail it.
 *
 * Before any of that, **every** call passes [site], on every route, keyed or not ([installSiteGate],
 * bee#45): loopback needs no key, so the key gate alone would let any site open in a browser on
 * the laptop reach the board.
 *
 * @param board the board this host keeps: the only one a socket may name
 * @param peers where each call came from; production passes [PeerAddress.DIRECT]
 * @param arrivals where each call arrived; production passes [ArrivalAddress.DIRECT]
 * @param desk serves the pairing channel, over [pairing]
 */
@Suppress("LongParameterList")
fun Application.mountEndpoints(
    board: BoardId,
    pairing: Pairing,
    site: SiteRule,
    peers: PeerAddress,
    arrivals: ArrivalAddress,
    desk: PairingDesk,
): HostEndpoints {
    installSiteGate(site, arrivals)
    installBoundedWebSockets()
    install(SSE)
    val relay = KtorConnectionSource(this, BoardRelay.PATH)
    val claims = KtorRoomHost(this, BeeHost.CLAIMS_PATH, BeeHost.CLAIMS_SERVER, Pattern(BeeHost.CLAIMS_SESSION))
    val keyRefusal = { call: ApplicationCall ->
        val keyed = isLoopback(peers.of(call)) || pairing.admits(call.request.queryParameters[PairingWire.KEY_PARAM])
        "This device needs a key: open the page to pair it.".takeUnless { keyed }
    }
    val refusal = { call: ApplicationCall ->
        if (call.request.queryParameters[BoardId.PARAM] != board.value) "This socket names another board, or none: reload the page." else keyRefusal(call)
    }
    routing {
        for (gated in listOf(BoardRelay.PATH, BeeHost.CLAIMS_PATH)) {
            route(gated) { install(SocketGate) { this.refusal = refusal } }
        }
        // MCP over HTTP names no board: the host's own bridge for it does, on its loopback sockets.
        route(BeeHost.MCP_PATH) { install(SocketGate) { this.refusal = keyRefusal } }
        // A page asks before it reads its copy, so this must never come from a cache.
        get(BoardId.PATH) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(board.value)
        }
        pairingEndpoint(desk, peers)
        staticResources("/", "web") { modify { _, call -> call.response.header(BeeHost.CSP_HEADER, BeeHost.PAGE_CSP) } }
    }
    return HostEndpoints(relay, claims)
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

/**
 * [SocketGate]'s rule: why a call may not open its socket, or null if it may. The default refuses
 * every call, so a gate installed unconfigured fails closed.
 */
class SocketGateConfig {
    var refusal: (ApplicationCall) -> String? = { "This socket's gate isn't configured." }
}

/** Refuses, with a 403, every call [SocketGateConfig.refusal] names a reason for, before the route's handler runs. */
private val SocketGate = createRouteScopedPlugin("BeeSocketGate", ::SocketGateConfig) {
    val refusal = pluginConfig.refusal
    onCall { call ->
        refusal(call)?.let { call.respond(HttpStatusCode.Forbidden, it) }
    }
}
