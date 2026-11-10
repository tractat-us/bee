package us.tractat.bee.host

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import us.tractat.bee.core.PairingEnd
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
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

/** Opens a pairing at [path] as the page does, `POST`ing it with [host] and these [headers]: what the host answered. */
internal fun Handshakes.openPairing(path: String, host: String, vararg headers: String): PairingPoll {
    val (status, body) = post(path, host, "", *headers)
    check(status == Handshakes.OK) { "rig: opening a pairing got $status: $body" }
    return Json.decodeFromString(PairingPoll.serializer(), body)
}

/** A pairing's first message, opened at [path] as [openPairing] opens it: its code, or why it was refused. */
internal fun Handshakes.firstMessage(path: String, host: String, vararg headers: String): PairingMessage =
    when (val opening = openPairing(path, host, *headers)) {
        is PairingPoll.Opened -> opening.code
        is PairingPoll.Refused -> opening.ended
    }

/** Asks after [token]'s pairing as the page does, with [host] and these [headers]: its newest message, or null once the host holds none. */
internal fun Handshakes.poll(token: String, host: String, vararg headers: String): PairingMessage? {
    val (status, body) = post(PairingWire.POLL_PATH, host, token, *headers)
    return when (status) {
        Handshakes.OK -> Json.decodeFromString(PairingMessage.serializer(), body)
        NOT_FOUND -> null
        else -> error("rig: a poll got $status: $body")
    }
}

/**
 * Opens a pairing at [path], naming the host [host] and sending these [headers], once the host's
 * roster holds a person [attachSomeone] attached: the roster reads the board a moment later, and
 * a refusal for nobody here opens no pairing, so asking again costs nothing.
 */
internal suspend fun Handshakes.pairOnceSomeoneIsHere(path: String, host: String, vararg headers: String): PairingPoll {
    repeat(ROSTER_TRIES) {
        val opening = openPairing(path, host, *headers)
        if (opening != NOBODY_HERE) return opening
        delay(100.milliseconds)
    }
    error("rig: the host's roster never saw the attached person")
}

private val NOBODY_HERE = PairingPoll.Refused(PairingMessage.Ended(PairingEnd.NobodyHere))
private const val ROSTER_TRIES = 50
private const val NOT_FOUND = "404"
