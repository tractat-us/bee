package us.tractat.bee.host

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PeerIdentity
import us.tractat.bee.core.PresenceEntry
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A person's page attached to this host's board, as its presence says, posted through the host's
 * own replica: on a hosted board, someone who can admit a pairing ([PeopleAttached]).
 */
internal suspend fun BeeHost.attachSomeone(name: String = "iain") {
    mutate { it.postPresence(ReplicaId("web-$name"), PresenceEntry(PeerIdentity.human(name), null, null, Clock.System.now(), waiting = false), 1) }
}

/** The next event on a pairing channel, as the [PairingMessage] it carries. */
internal fun Handshakes.Events.message(): PairingMessage = Json.decodeFromString(PairingMessage.serializer(), next())

/**
 * Opens a pairing channel at [path], naming the host [host] and sending these [headers], once the
 * host's roster holds a person [attachSomeone] attached: the roster reads the board a moment
 * later, and a refusal for nobody here opens no pairing, so asking again costs nothing.
 */
internal suspend fun Handshakes.pairOnceSomeoneIsHere(path: String, host: String, vararg headers: String): Pair<Handshakes.Events, PairingMessage> {
    repeat(ROSTER_TRIES) {
        val events = events(path, host, *headers)
        val message = events.message()
        if (message != PairingMessage.Ended(PairingEnd.NobodyHere)) return events to message
        events.close()
        delay(100.milliseconds)
    }
    error("rig: the host's roster never saw the attached person")
}

private const val ROSTER_TRIES = 50
