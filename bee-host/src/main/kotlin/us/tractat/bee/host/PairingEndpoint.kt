package us.tractat.bee.host

import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * The pairing channel at [PairingWire.PATH] (spec §5.7, step 1): a keyless device opens it, gets a
 * code to show, and waits for a key. It is a stream of server-sent events, each one
 * [PairingMessage], and nothing else; [desk] serves each device.
 *
 * Not a WebSocket: the host reads nothing from a keyless device, so it gives one no inbound frame
 * parser to feed. When the device hangs up, Netty cancels its call ([HttpRequestLifecycle]), and
 * that cancellation is how [desk] learns it left.
 *
 * Needs Ktor's `SSE` plugin installed on the application.
 */
fun Route.pairingEndpoint(desk: PairingDesk, peers: PeerAddress) {
    route(PairingWire.PATH) {
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
        sse {
            val device = Device(peers.of(call), screenName(call.request.queryParameters[PairingWire.SCREEN_PARAM]))
            desk.serve(device) { send(data = Json.encodeToString(PairingMessage.serializer(), it)) }
        }
    }
}

/**
 * Serves one keyless device's pairing, from its code to its last message, apart from the channel.
 *
 * Each device that [Pairing.open] lets in gets a code. Its join request goes on the board only once
 * it has stayed on for [dwell], so a device that dials and drops writes nothing there. The pairing
 * ends when a person types the code (a [PairingMessage.Key]), when it is voided or its time runs
 * out (a [PairingMessage.Ended]), or when the device goes away, which closes its request.
 *
 * **The device going away is the cancellation of [serve].** Whatever [serve] is doing when it is
 * cancelled, it ends the pairing and withdraws a posted request on the way out.
 *
 * **Someone who can admit must be here** ([admitters]). On a hosted board a pairing opens only
 * while a person is attached, its request is posted only if one still is after [dwell], and it
 * ends [PairingEnd.NobodyHere] once the last one leaves, so no request outlives its last admitter
 * (Iain, 2026-11-08). A LAN host's [Admitters.WHOEVER_COMES] lets a request wait for whoever comes.
 *
 * @param joinRequests the board side of pairing, ready once the relay is up
 * @param checkEvery how often a posted request asks [admitters] whether anyone is still here
 */
class PairingDesk(
    private val pairing: Pairing,
    private val joinRequests: Deferred<JoinRequestSurface>,
    private val dwell: Duration,
    private val clock: () -> Instant,
    private val admitters: Admitters,
    private val checkEvery: Duration = Admitters.CHECK_EVERY,
) {
    /** Serves [device] until its pairing ends; [send] writes to its channel. Cancel it when the device leaves. */
    suspend fun serve(device: Device, send: suspend (PairingMessage) -> Unit) {
        if (!admitters.anyHere()) return send(NOBODY_HERE)
        val ticket = when (val opened = pairing.open(device.caller)) {
            is Opened.Refused -> return send(PairingMessage.Ended(opened.wire))
            is Opened.Ticket -> opened
        }
        try {
            send(PairingMessage.Code(ticket.id.value, ticket.code, ticket.expiresAt))
            delay(dwell)
            send(if (admitters.anyHere()) onTheBoard(ticket, device, joinRequests.await()) else NOBODY_HERE)
        } finally {
            // A device gone before its request was posted leaves no pairing behind.
            pairing.end(ticket.id)
        }
    }

    /** Posts [ticket]'s request and waits for it to end: the device's last message. */
    private suspend fun onTheBoard(ticket: Opened.Ticket, device: Device, requests: JoinRequestSurface): PairingMessage = coroutineScope {
        val resolution = requests.post(ticket, device)
        // The last admitter leaving ends the request. An entry that won the race already resolved
        // it, and `end` leaves it be.
        val watch = launch { admitters.awaitNobody(checkEvery).also { requests.end(ticket.id, PairingEnd.NobodyHere) } }
        try {
            // On the timer, expire the pairing, likewise.
            val ended = withTimeoutOrNull(ticket.expiresAt - clock()) { resolution.await() }
                ?: requests.end(ticket.id, PairingEnd.Expired).let { resolution.await() }
            when (ended) {
                is Resolution.Admitted -> PairingMessage.Key(ended.key.value)
                is Resolution.Ended -> PairingMessage.Ended(ended.reason)
            }
        } finally {
            watch.cancel()
            requests.withdraw(ticket.id)
        }
    }

    private companion object {
        val NOBODY_HERE = PairingMessage.Ended(PairingEnd.NobodyHere)
    }
}

/** [raw] as a room-screen name, if it is one a peer identity could carry (A7); otherwise none. */
private fun screenName(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SCREEN_NAME && ':' !in it && '#' !in it }
