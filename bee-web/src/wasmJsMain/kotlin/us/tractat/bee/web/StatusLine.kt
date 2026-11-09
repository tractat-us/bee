package us.tractat.bee.web

import us.tractat.bee.core.PairingWire
import us.tractat.bee.core.PeerIdentity

/**
 * What the bar's `#status` says: short [text] that never pushes the bar off a screen, and the
 * last failure's [detail] for its tooltip and the log only. In a browser a socket's failure
 * message is often a serialized JS event, which is no sentence for a person; and it may name the
 * socket's URL, so any key in it is cut out.
 */
internal data class StatusLine(val text: String, val detail: String) {
    companion object {
        private val KEY = Regex("([?&]${PairingWire.KEY_PARAM}=)[^&\\s\"'\\\\]*")

        fun of(online: Boolean, actor: PeerIdentity, failure: Throwable?): StatusLine = when {
            online -> StatusLine("online as $actor", "")
            failure != null -> StatusLine("offline — reconnecting…", withoutKey(failure.message ?: failure::class.simpleName.orEmpty()))
            else -> StatusLine("offline", "")
        }

        private fun withoutKey(text: String): String = KEY.replace(text, "$1…")
    }
}
