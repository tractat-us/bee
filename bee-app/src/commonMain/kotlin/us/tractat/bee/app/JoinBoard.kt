package us.tractat.bee.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import kotlinx.coroutines.CoroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
    ).also { client ->
        client.connect()
        scope.launch { client.online.collect { logger.info { if (it) "Online on $host." else "Offline from $host." } } }
        // A dial's failure can name the URL it dialled, key and all: log it as the host describes it.
        scope.launch { client.lastFailure.filterNotNull().collect { logger.warn { "The board link failed. ${host.describe(it)}" } } }
    }
}

private val logger = KotlinLogging.logger {}

/** The host's board relay socket: bee-host's `BoardRelay.PATH`. */
private const val BOARD_SOCKET = "/ws/board"
