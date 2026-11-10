package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the host tells a keyless device about its pairing (spec §5.7), as the JSON body of a
 * [PairingWire.POLL_PATH] answer. Pairing carries these and nothing else: no board state, no
 * presence, no claims.
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
 * What `POST` [PairingWire.PATH] answers (bee#209). Pairing by poll is plain requests and complete
 * responses, so a proxy that holds back a stream until it ends can't stall it.
 */
@Serializable
sealed interface PairingPoll {
    /**
     * A pairing opened: [code] to show, and the [token] that fetches what follows from
     * [PairingWire.POLL_PATH]. [token] is the poll's only capability, and a secret: unlike the
     * pairing id, it never goes on the board. Whoever holds it can fetch the pairing's key.
     */
    @Serializable
    @SerialName("opened")
    data class Opened(val token: String, val code: PairingMessage.Code) : PairingPoll {
        override fun toString(): String = "Opened(token=…, code=…, expiresAt=${code.expiresAt})"
    }

    /** No pairing opened, for [ended]'s reason. The host holds nothing for this device. */
    @Serializable
    @SerialName("refused")
    data class Refused(val ended: PairingMessage.Ended) : PairingPoll
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
    /** Where a keyless device opens a pairing: `POST` it, and get a [PairingPoll]. */
    const val PATH: String = "/pair"

    /**
     * Where a device pairing by poll asks what its pairing has come to: `POST` its
     * [PairingPoll.Opened.token] as the body, and get the newest [PairingMessage], or a 404 once
     * the host holds no pairing for that token.
     */
    const val POLL_PATH: String = "/pair/poll"

    /** How often a device pairing by poll asks, counted from the last answer, so asks never overlap. */
    val POLL_EVERY: Duration = 1.seconds

    /** How long a device waits for a pairing request's answer before it counts the request lost. */
    val REQUEST_TIMEOUT: Duration = 4.seconds

    /**
     * How long a pairing by poll lasts with no poll reaching the host, and how long a device keeps
     * asking with no answer reaching it. Past it the host ends the pairing, as a hang-up would, and
     * the device stops. Room for two polls in a row to time out and a third to arrive in time.
     */
    val IDLE: Duration = (POLL_EVERY + REQUEST_TIMEOUT) * 3

    /** The query parameter carrying a device key, on the page's URL and on every socket it dials. */
    const val KEY_PARAM: String = "k"

    /** The query parameter naming a room screen (A7), on the page's URL and the pairing channel. */
    const val SCREEN_PARAM: String = "screen"
}
