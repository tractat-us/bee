package us.tractat.bee.host

import io.ktor.http.Headers
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

/**
 * Who made a call: the [address] it came from, and whether it is [local], this machine's own,
 * which reaches the board with no key ([mountEndpoints]). Only [byPeer] and [forwarded] make one,
 * so a caller is local only when the TCP peer itself is a loopback address and no proxy stood
 * between: a forwarded address is whatever the proxy, or a dialler posing as one, says.
 */
@ConsistentCopyVisibility
data class Caller private constructor(val address: String, val local: Boolean) {
    override fun toString(): String = if (local) "Caller($address, local)" else "Caller($address)"

    companion object {
        /** A caller known by its TCP peer's [address]: local when that is a loopback address ([isLoopback]). */
        fun byPeer(address: String): Caller = Caller(address, isLoopback(address))

        /** A caller a proxy names: never local, whatever [address] says. */
        fun forwarded(address: String): Caller = Caller(address, local = false)
    }
}

/** Who each call came from ([Caller]). */
fun interface PeerAddress {
    fun of(call: ApplicationCall): Caller

    companion object {
        /**
         * The TCP peer, never a forwarded header: no proxy stands in front of the host, and a
         * header is whatever the dialler says it is.
         */
        val DIRECT: PeerAddress = PeerAddress { Caller.byPeer(it.request.local.remoteAddress) }
    }
}

/**
 * Behind one trusted proxy ([Reach.Public]): each call's caller is the client the proxy names, read
 * the way that proxy writes it ([AppendedForwardedFor] or [ClientHeader]). A call where the proxy
 * names no usable client is its TCP peer's.
 *
 * **No call is ever [Caller.local] here**, whatever its TCP peer or its headers: the proxy dials
 * from wherever it runs, a header claiming loopback came from someone who isn't, and on a hosted
 * machine loopback is the host's own processes, which hold keys like anyone. That decision is
 * [of]'s alone; a variant only reads the client's address.
 */
sealed class ForwardedPeer : PeerAddress {
    /** The client the proxy names in [headers], or null when it names none usable. */
    protected abstract fun client(headers: Headers): String?

    final override fun of(call: ApplicationCall): Caller = Caller.forwarded(client(call.request.headers) ?: call.request.local.remoteAddress)

    /**
     * A proxy that appends the client it saw to `X-Forwarded-For`: the rightmost entry, since every
     * entry left of it is whatever the client sent. Not Fly's proxy, whose rightmost entry is the
     * app's own address, the same for every client ([ClientHeader]).
     */
    data object AppendedForwardedFor : ForwardedPeer() {
        override fun client(headers: Headers): String? =
            headers.getAll(FORWARDED_FOR).orEmpty().flatMap { it.split(',') }.map(String::trim).lastOrNull { it.isNotEmpty() }
    }

    /**
     * `--client-ip-header <name>`: a proxy that sets the header [name] to the client's address
     * alone, as Fly's sets `Fly-Client-IP`. Its value names the client when it is one IP literal,
     * trimmed; anything else names none.
     */
    data class ClientHeader(val name: String) : ForwardedPeer() {
        init {
            require(FIELD_NAME.matches(name)) { "Not a header name: $name" }
        }

        override fun client(headers: Headers): String? = headers.getAll(name)?.singleOrNull()?.trim()?.takeIf(::isIpLiteral)
    }

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"

        /** An HTTP field name: RFC 9110's `token`. */
        val FIELD_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}

/** Whether [literal] is an IP address, written as one. A host name is not, and nor is anything unparseable. */
fun isIpLiteral(literal: String): Boolean = try {
    InetAddress.ofLiteral(literal)
    true
} catch (_: IllegalArgumentException) {
    false
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
 *   from a [Caller.local] caller or hold a key [Pairing] minted in its `?k=`. Anything else gets a 403
 *   before the WebSocket upgrade, so it never reaches the hub or the voter, and a replica of
 *   another board never pushes its state.
 * - MCP over Streamable HTTP at [BeeHost.MCP_PATH] (served by [us.tractat.bee.mcp.HttpBridges]),
 *   behind the key half of the same gate: from a local caller, or with a key [Pairing] minted in its
 *   `?k=`. It names no board, since the bridge the host runs for it dials the board itself.
 * - the board's id at [BoardId.PATH], the pairing channel at [PairingWire.PATH] and the page at
 *   `/`, open to all: they are what a keyless device needs, and none carries board state, presence
 *   or claims. The page's files, and only they, carry [BeeHost.PAGE_CSP].
 * - the join link at [us.tractat.bee.core.InviteLinks.PATH], to a local caller alone, and only
 *   when [invitation] is open ([inviteEndpoint]).
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
 * @param peers who each call came from; production passes its [Reach.peers]
 * @param arrivals where each call arrived; production passes [ArrivalAddress.DIRECT]
 * @param desk serves the pairing channel, over [pairing]
 * @param invitation whether the host has a join link to give its owner's page
 */
@Suppress("LongParameterList")
fun Application.mountEndpoints(
    board: BoardId,
    pairing: Pairing,
    site: SiteRule,
    peers: PeerAddress,
    arrivals: ArrivalAddress,
    desk: PairingDesk,
    invitation: Invitation,
): HostEndpoints {
    installSiteGate(site, arrivals)
    installBoundedWebSockets()
    install(SSE)
    val relay = KtorConnectionSource(this, BoardRelay.PATH)
    val claims = KtorRoomHost(this, BeeHost.CLAIMS_PATH, BeeHost.CLAIMS_SERVER, Pattern(BeeHost.CLAIMS_SESSION))
    val keyRefusal = { call: ApplicationCall ->
        val keyed = peers.of(call).local || pairing.admits(call.request.queryParameters[PairingWire.KEY_PARAM])
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
        inviteEndpoint(invitation, pairing, peers, arrivals)
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
