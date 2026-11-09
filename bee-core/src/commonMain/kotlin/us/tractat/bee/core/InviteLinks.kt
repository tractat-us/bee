package us.tractat.bee.core

import kotlinx.serialization.Serializable

/**
 * The host's join link, for the board owner's own page to show as a QR code: the JSON body of
 * `GET` [PATH]. The host answers only a call from this machine, and only when it was started with
 * `--lan`: a page reached over the network already holds a key and doesn't pass it on, and a host
 * on loopback alone has no link a device off the machine could open.
 *
 * @param numeric the link at each IPv4 address the host has on the network, the likeliest first:
 *   what a phone opens, since not every phone resolves `.local` names
 * @param named the link at the host's `.local` name, or a hosted board's public URL
 * @param joins where a device that opens the link can join from: the room's network, or anywhere
 *   for a hosted board (bee#145), which any admitted page may ask, and whose answer is its bare
 *   public URL: the page mints the link it shows ([InviteWire], bee#215)
 */
@Serializable
data class InviteLinks(val numeric: List<String>, val named: String, val joins: Joins = Joins.OnThisNetwork) {
    /** The one link a QR code carries: the first numeric one, else the named one. */
    val primary: String get() = numeric.firstOrNull() ?: named

    /** Where a device that opens the link can join from. */
    @Serializable
    enum class Joins {
        /** A `--lan` host: a phone or laptop on the room's network. */
        OnThisNetwork,

        /**
         * A hosted board, behind its public URL: a phone or laptop anywhere. Its link is minted,
         * by `POST` [PATH], when the page's Invite opens ([MintedInvite]).
         */
        Anywhere,
    }

    companion object {
        const val PATH: String = "/invite"
    }
}
