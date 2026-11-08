package us.tractat.bee.host

import io.ktor.server.routing.Route
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
import kotlin.time.Duration
import kotlin.time.Instant

/** The longest room-screen name a join request shows. */
private const val MAX_SCREEN_NAME = 40

/**
 * The pairing socket at [PairingWire.PATH] (spec §5.7, step 1): a keyless device dials it, gets a
 * code to show, and waits for a key. It carries [PairingMessage]s and nothing else; [desk] serves
 * each one.
 */
fun Route.pairingEndpoint(desk: PairingDesk, peers: PeerAddress) {
    webSocket(PairingWire.PATH) {
        val device = Device(peers.of(call), screenName(call.request.queryParameters[PairingWire.SCREEN_PARAM]))
        // The device sends nothing; this completes when it closes its socket.
        val left = async { for (frame in incoming) Unit }
        try {
            desk.serve(device, left) { send(Frame.Text(Json.encodeToString(PairingMessage.serializer(), it))) }
        } finally {
            left.cancel()
        }
        close()
    }
}

/**
 * Serves one keyless device's pairing, from its code to its last message, apart from the socket.
 *
 * Each device that [Pairing.open] lets in gets a code. Its join request goes on the board only once
 * it has held its socket open for [dwell], so a device that dials and drops writes nothing there.
 * The pairing ends when a person types the code (a [PairingMessage.Key]), when it is voided or its
 * time runs out (a [PairingMessage.Ended]), or when the device goes away, which closes its request.
 *
 * @param joinRequests the board side of pairing, ready once the relay is up
 */
class PairingDesk(
    private val pairing: Pairing,
    private val joinRequests: Deferred<JoinRequestSurface>,
    private val dwell: Duration,
    private val clock: () -> Instant,
) {
    /** Serves [device], whose socket closing completes [left]; [send] writes to its socket. */
    suspend fun serve(device: Device, left: Deferred<Unit>, send: suspend (PairingMessage) -> Unit) {
        val ticket = when (val opened = pairing.open(device.address)) {
            is Opened.Refused -> return send(PairingMessage.Ended(PairingEnd.Busy))
            is Opened.Ticket -> opened
        }
        try {
            send(PairingMessage.Code(ticket.id.value, ticket.code, ticket.expiresAt))
            val droppedEarly = withTimeoutOrNull(dwell) { left.await() } != null
            if (droppedEarly) return
            onTheBoard(ticket, device, joinRequests.await(), left)?.let { send(it) }
        } finally {
            // A device gone before its request was posted leaves no pairing behind.
            pairing.end(ticket.id)
        }
    }

    /** Posts [ticket]'s request and waits for it to end: the device's last message, or null if it left. */
    private suspend fun onTheBoard(ticket: Opened.Ticket, device: Device, requests: JoinRequestSurface, left: Deferred<Unit>): PairingMessage? {
        val resolution = requests.post(ticket, device)
        try {
            val waited = withTimeoutOrNull(ticket.expiresAt - clock()) {
                select<Waited> { resolution.onAwait { Waited.Resolved(it) }; left.onAwait { Waited.Left } }
            }
            val ended = when (waited) {
                Waited.Left -> return null
                is Waited.Resolved -> waited.resolution
                // On the timer, expire the pairing. An entry that won the race already resolved
                // it, and `end` leaves it be.
                null -> requests.end(ticket.id, PairingEnd.Expired).let { resolution.await() }
            }
            return when (ended) {
                is Resolution.Admitted -> PairingMessage.Key(ended.key.value)
                is Resolution.Ended -> PairingMessage.Ended(ended.reason)
            }
        } finally {
            requests.withdraw(ticket.id)
        }
    }

    private sealed interface Waited {
        data class Resolved(val resolution: Resolution) : Waited

        data object Left : Waited
    }
}

/** [raw] as a room-screen name, if it is one a peer identity could carry (A7); otherwise none. */
private fun screenName(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SCREEN_NAME && ':' !in it && '#' !in it }
