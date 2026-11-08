package us.tractat.bee.host

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingWire
import kotlin.time.Instant

/** The longest room-screen name a join request shows. */
private const val MAX_SCREEN_NAME = 40

/**
 * The pairing socket at [PairingWire.PATH] (spec §5.7, step 1): a keyless device dials it, gets a
 * code to show, and waits for a key. It carries [PairingMessage]s and nothing else.
 *
 * Each socket opens one pairing and puts its join request on the board. The pairing ends when a
 * person types the code (a [PairingMessage.Key]), when it is voided or its time runs out (a
 * [PairingMessage.Ended]), or when the device goes away, which closes its request.
 */
fun Route.pairingEndpoint(pairing: Pairing, peers: PeerAddress, joinRequests: Deferred<JoinRequestSurface>, clock: () -> Instant) {
    webSocket(PairingWire.PATH) {
        val device = Device(peers.of(call), screenName(call.request.queryParameters[PairingWire.SCREEN_PARAM]))
        when (val opened = pairing.open()) {
            Opened.Busy -> send(PairingMessage.Ended(PairingEnd.Busy))
            is Opened.Ticket -> try {
                send(PairingMessage.Code(opened.id.value, opened.code, opened.expiresAt))
                pair(opened, device, joinRequests.await(), clock)
            } finally {
                // A device gone before its request was posted leaves no pairing behind.
                pairing.end(opened.id)
            }
        }
        close()
    }
}

private suspend fun DefaultWebSocketServerSession.pair(
    ticket: Opened.Ticket,
    device: Device,
    requests: JoinRequestSurface,
    clock: () -> Instant,
) {
    val resolution = requests.post(ticket, device)
    try {
        // The device sends nothing; this ends when it closes its socket.
        val left = async { for (frame in incoming) Unit }
        val outcome = withTimeoutOrNull(ticket.expiresAt - clock()) {
            select { resolution.onAwait { it }; left.onAwait { null } }
        }
        left.cancel()
        if (outcome == null && left.isCompleted) return
        // On the timer, expire the pairing. An entry that won the race already resolved it, and
        // `end` leaves it be.
        val ended = outcome ?: requests.end(ticket.id, PairingEnd.Expired).let { resolution.await() }
        send(
            when (ended) {
                is Resolution.Admitted -> PairingMessage.Key(ended.key.value)
                is Resolution.Ended -> PairingMessage.Ended(ended.reason)
            },
        )
    } finally {
        requests.withdraw(ticket.id)
    }
}

private suspend fun DefaultWebSocketServerSession.send(message: PairingMessage) =
    send(Frame.Text(Json.encodeToString(PairingMessage.serializer(), message)))

/** [raw] as a room-screen name, if it is one a peer identity could carry (A7); otherwise none. */
private fun screenName(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SCREEN_NAME && ':' !in it && '#' !in it }
