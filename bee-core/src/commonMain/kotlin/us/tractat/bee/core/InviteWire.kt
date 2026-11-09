package us.tractat.bee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A hosted board's invite (bee#215): a link that admits whoever opens it, for a day.
 *
 * An admitted page `POST`s [InviteLinks.PATH], with its key in `?k=`, and gets a [MintedInvite]:
 * `https://<public>/#i=<secret>`. The secret rides in the fragment, which a browser never sends, so
 * it reaches no proxy or log. The page that opens the link moves it out of the address bar, asks a
 * name, and `POST`s a [Redemption] to [REDEEM_PATH], in the body and never the query. It gets
 * [Redeemed.Key], a device key of its own like the one pairing gives, or [Redeemed.Refused].
 */
object InviteWire {
    /** Where a page redeems an invite: `POST` a [Redemption], get a [Redeemed]. */
    const val REDEEM_PATH: String = "/invite/redeem"

    /** The fragment parameter that carries an invite's secret: `#i=<secret>`. */
    const val SECRET_PARAM: String = "i"

    /** The longest name a person joining by invite may take. */
    const val MAX_NAME: Int = 40

    /**
     * [raw], trimmed, as the name of a person `human:<name>` (A7), or null if it can't be one: empty,
     * longer than [MAX_NAME], or holding `:`, `#` or a control character, which a log line would carry.
     */
    fun nameOrNull(raw: String?): String? =
        raw?.trim()?.takeIf { name -> name.isNotEmpty() && name.length <= MAX_NAME && name.none { it == ':' || it == '#' || it.isISOControl() } }
}

/** What minting an invite gave: the [link] to share, good until [expiresAt]. Its [toString] never shows the link. */
@Serializable
data class MintedInvite(val link: String, val expiresAt: Instant) {
    override fun toString(): String = "MintedInvite(link=…, expiresAt=$expiresAt)"
}

/** A page redeeming the invite whose secret is [invite], for a person named [name]. */
@Serializable
data class Redemption(val invite: String, val name: String) {
    override fun toString(): String = "Redemption(invite=…, name=$name)"
}

/** What redeeming an invite came to. */
@Serializable
sealed interface Redeemed {
    /** [key] admits this device to the board and claims endpoints, as a key from pairing does. */
    @Serializable
    @SerialName("key")
    data class Key(val key: String) : Redeemed {
        override fun toString(): String = "Key(…)"
    }

    /** No key, for [reason]. The page offers pairing by code instead. */
    @Serializable
    @SerialName("refused")
    data class Refused(val reason: InviteEnd) : Redeemed
}

/** Why an invite gave no key. */
enum class InviteEnd {
    /** The board made no invite with this secret, or forgot it long ago. */
    Unknown,

    /** The invite's day is over. */
    Lapsed,

    /** As many devices as one invite admits have joined by it. */
    Used,

    /** This device sent too many secrets the board never made; it waits a while. */
    TooManyTries,

    /** The name isn't one a person can take ([InviteWire.nameOrNull]). */
    BadName,
}
