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
 * @param named the link at the host's `.local` name
 */
@Serializable
data class InviteLinks(val numeric: List<String>, val named: String) {
    /** The one link a QR code carries: the first numeric one, else the named one. */
    val primary: String get() = numeric.firstOrNull() ?: named

    companion object {
        const val PATH: String = "/invite"
    }
}
