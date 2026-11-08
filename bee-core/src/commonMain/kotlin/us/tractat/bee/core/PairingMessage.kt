package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * What the host sends a keyless device down the pairing socket (spec §5.7), as JSON text frames.
 * The socket carries these and nothing else: no board state, no presence, no claims.
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

/** Why a pairing ended without a key. */
enum class PairingEnd {
    /** Nobody typed the code within its two minutes. */
    Expired,

    /** Three wrong codes were typed. */
    Voided,

    /** Too many devices are pairing at once; try again shortly. */
    Busy,
}

/** The pairing wire's coordinates, shared by the host and the page. */
object PairingWire {
    /** The pairing socket's route. */
    const val PATH: String = "/ws/pair"

    /** The query parameter carrying a device key, on the page's URL and on every socket it dials. */
    const val KEY_PARAM: String = "k"

    /** The query parameter naming a room screen (A7), on the page's URL and the pairing socket. */
    const val SCREEN_PARAM: String = "screen"
}
