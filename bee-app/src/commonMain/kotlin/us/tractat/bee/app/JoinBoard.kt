package us.tractat.bee.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.BoardPin
import us.tractat.bee.core.HostAddress
import us.tractat.bee.core.SpokeLoom
import us.tractat.bee.core.askBoardId
import us.tractat.bee.core.pinnedTo
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The HTTP client the app dials a host with. Its WebSockets ping every 5 s, as a bridge's do
 * (bee-mcp's `PEER_PING_INTERVAL`, bee#77), so a link to a host that vanished without a FIN is
 * torn down and redialled instead of waiting out TCP. The caller owns it and closes it.
 */
fun appHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) { pingInterval = 5.seconds } }

/**
 * The app's replica of the board at [host], connected: it dials the host's board relay and keeps
 * redialling until the client is closed. It belongs to the first board the host names, as a
 * bridge's does (bee#46). The caller owns [http] and [scope].
 */
fun joinBoard(
    host: HostAddress,
    http: HttpClient,
    scope: CoroutineScope,
    author: ReplicaId,
    clock: () -> Instant,
    random: Random,
): BoardClient {
    val pin = BoardPin.learnedFrom { http.askBoardId(host) }
    return BoardClient(
        author = author,
        loom = SpokeLoom(http, Dispatchers.Default, scope, author.value, random, clock).pinnedTo(pin),
        rendezvous = Rendezvous.Existing(WebSocketAdvertisement(host.socket(BOARD_SOCKET), PeerId("bee-host"), "board")),
        scope = scope,
        clock = clock,
    ).also { it.connect() }
}

/** The host's board relay socket: bee-host's `BoardRelay.PATH`. */
private const val BOARD_SOCKET = "/ws/board"
