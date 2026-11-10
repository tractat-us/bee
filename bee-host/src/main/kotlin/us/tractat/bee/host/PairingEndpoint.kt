package us.tractat.bee.host

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import kotlin.time.Duration
import kotlin.time.Instant

/** The longest room-screen name a join request shows. */
private const val MAX_SCREEN_NAME = 40

/** The longest body a poll may carry: a token is 43 bytes. A longer one is refused, unread. */
private const val MAX_TOKEN_BYTES = 64

/**
 * The pairing channel (spec §5.7, step 1), pairing by poll (bee#209): a keyless device `POST`s
 * [PairingWire.PATH] for a code to show and a token, then `POST`s the token to
 * [PairingWire.POLL_PATH] to ask what came of it, until it gets a key or the pairing ends. Every
 * call is a plain request and a complete response, so a proxy that holds a stream back until it
 * ends can't leave a device waiting. [polls] holds each pairing between polls, and its [PairingDesk] serves it.
 *
 * Not a stream and not a WebSocket: one path, with one failure mode (bee#211). The host reads
 * nothing from a keyless device but a bounded token, so it gives one no frame parser to feed.
 */
fun Route.pairingEndpoint(polls: PairingPolls, peers: PeerAddress) {
    post(PairingWire.PATH) {
        val opened = polls.open(device(call, peers))
        call.respondJson(Json.encodeToString(PairingPoll.serializer(), opened))
    }
    post(PairingWire.POLL_PATH) {
        val token = call.receiveAtMost(MAX_TOKEN_BYTES)
            ?: return@post call.respondText("A poll carries its token and nothing more.", status = HttpStatusCode.PayloadTooLarge)
        when (val message = polls.poll(token)) {
            null -> call.respondText("This pairing is over: ask for a new code.", status = HttpStatusCode.NotFound)
            else -> call.respondJson(Json.encodeToString(PairingMessage.serializer(), message))
        }
    }
}

private fun device(call: ApplicationCall, peers: PeerAddress) =
    Device(peers.of(call), screenName(call.request.queryParameters[PairingWire.SCREEN_PARAM]))

/**
 * This call's body as text, or null if it is longer than [max] bytes. Anyone may call a pairing or invite
 * route, so the host reads at most [max] + 1 bytes of a body, whatever its length says.
 */
internal suspend fun ApplicationCall.receiveAtMost(max: Int): String? {
    if ((request.contentLength() ?: 0) > max) return null
    val body = receiveChannel().readRemaining(max + 1L).readByteArray()
    return body.takeIf { it.size <= max }?.decodeToString()
}

/** Answers [json], which may carry a key, past every cache. */
private suspend fun ApplicationCall.respondJson(json: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(json, ContentType.Application.Json)
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
    internal val clock: () -> Instant,
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
