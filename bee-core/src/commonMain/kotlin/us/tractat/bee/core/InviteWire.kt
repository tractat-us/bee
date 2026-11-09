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
     * longer than [MAX_NAME], or holding `:`, `#` or an [invisible] code point, which a log line
     * would carry or which can make one name look like another. Lookalike letters still pass: no
     * rule by code point catches those.
     */
    fun nameOrNull(raw: String?): String? =
        raw?.trim()?.takeIf { name -> name.isNotEmpty() && name.length <= MAX_NAME && codePoints(name).none { it == COLON || it == HASH || invisible(it) } }

    /**
     * Whether [codePoint] shows nothing, or moves what's around it: a control or format character, a
     * line or paragraph separator, a lone surrogate, a variation selector, a Hangul filler, or
     * anything in plane 14 (tags and the supplementary variation selectors). Judged by code point,
     * so a format character outside the BMP, which UTF-16 splits in two, is caught too.
     */
    internal fun invisible(codePoint: Int): Boolean = when {
        codePoint > MAX_BMP -> codePoint in PLANE_14 || SUPPLEMENTARY_FORMAT.any { codePoint in it }
        else -> codePoint.toChar().category in UNNAMEABLE || codePoint in FILLERS || VARIATION_SELECTORS.any { codePoint in it }
    }

    /** [text]'s code points: a surrogate pair is one, and a surrogate left alone is itself. */
    internal fun codePoints(text: String): List<Int> = buildList {
        var i = 0
        while (i < text.length) {
            val high = text[i]
            val low = text.getOrNull(i + 1)
            if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                add(SUPPLEMENTARY_BASE + ((high.code - Char.MIN_HIGH_SURROGATE.code) shl SURROGATE_BITS) + (low.code - Char.MIN_LOW_SURROGATE.code))
                i += 2
            } else {
                add(high.code)
                i += 1
            }
        }
    }

    private const val COLON = ':'.code
    private const val HASH = '#'.code
    private const val MAX_BMP = 0xFFFF
    private const val SUPPLEMENTARY_BASE = 0x10000
    private const val SURROGATE_BITS = 10

    private val UNNAMEABLE = setOf(CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR, CharCategory.SURROGATE)

    /** Hangul fillers: letters (`Lo`) that draw nothing. */
    private val FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

    /** Variation selectors (`Mn`), Mongolian's free ones included. */
    private val VARIATION_SELECTORS = listOf(0x180B..0x180F, 0xFE00..0xFE0F)

    /** Plane 14: tag characters and the supplementary variation selectors. */
    private val PLANE_14 = 0xE0000..0xEFFFF

    /** The `Cf` code points outside the BMP and plane 14 (Unicode 16). */
    private val SUPPLEMENTARY_FORMAT = listOf(0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A)
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

    /** The board has let in as many devices by invite as it does in a day. */
    DailyLimit,

    /** The board couldn't record the redemption, so it gave no key; try again in a moment. */
    Unavailable,

    /** The name isn't one a person can take ([InviteWire.nameOrNull]). */
    BadName,
}
