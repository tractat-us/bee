package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the host sends a keyless device on the pairing channel (spec §5.7), as the JSON data of
 * server-sent events. The channel carries these and nothing else: no board state, no presence, no
 * claims.
 */
@Serializable
sealed interface PairingMessage {
    /** The code to show full screen, and when it stops working. The code never goes on the board. */
    @Serializable
    @SerialName("code")
    data class Code(val pairing: String, val code: String, val expiresAt: Instant) : PairingMessage

    /** A person typed the code: [key] admits this device to the board and claims endpoints. */
    @Serializable
    @SerialName("key")
    data class Key(val key: String) : PairingMessage

    /** The pairing ended without a key. The device may ask for a new code. */
    @Serializable
    @SerialName("ended")
    data class Ended(val reason: PairingEnd) : PairingMessage
}

/**
 * What `POST` [PairingWire.PATH] answers: the pairing's [message] so far, and the [token] that
 * fetches what follows from [PairingWire.POLL_PATH] (bee#209). Pairing by poll is plain requests
 * and complete responses, so a proxy that holds back a stream until it ends can't stall it.
 *
 * [token] is the poll's only capability, and a secret: unlike the pairing id, it never goes on
 * the board. Whoever holds it can fetch the pairing's key.
 */
@Serializable
data class PairingPoll(val token: String, val message: PairingMessage) {
    override fun toString(): String = "PairingPoll(token=…, message=${message::class.simpleName})"
}

/** Why a pairing ended without a key. */
enum class PairingEnd {
    /** Nobody typed the code within its two minutes. */
    Expired,

    /** Three wrong codes were typed. */
    Voided,

    /** The host is pairing as many devices as it will for now; try again later. */
    Busy,

    /** This device asked too soon after its last code, or while one was still waiting; wait a moment. */
    TooSoon,

    /**
     * On a hosted board, nobody who can admit a device is on it now, or the last one left while
     * this request was up: nothing waits on the board for someone who isn't there (bee#145).
     */
    NobodyHere,
}

/** The pairing wire's coordinates, shared by the host and the page. */
object PairingWire {
    /**
     * The pairing channel's route. `GET` is a server-sent event stream, not a WebSocket. `POST`
     * opens a pairing by poll instead, and answers a [PairingPoll].
     */
    const val PATH: String = "/pair"

    /**
     * Where a device pairing by poll asks what its pairing has come to: `POST` its
     * [PairingPoll.token] as the body, and get the newest [PairingMessage], or a 404 once the
     * host holds no pairing for that token.
     */
    const val POLL_PATH: String = "/pair/poll"

    /** How often a device pairing by poll asks. A pairing nobody asks after for a few of these ends, as a hang-up does. */
    val POLL_EVERY: Duration = 1.seconds

    /** The query parameter carrying a device key, on the page's URL and on every socket it dials. */
    const val KEY_PARAM: String = "k"

    /** The query parameter naming a room screen (A7), on the page's URL and the pairing channel. */
    const val SCREEN_PARAM: String = "screen"
}
