package us.tractat.bee.host

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import us.tractat.bee.core.LivePresence
import us.tractat.bee.core.PeerKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether anyone who can admit a device is attached to the board now ([PairingDesk]). On a hosted
 * board a request nobody can answer is refused at once, since Iain can't approve someone while he
 * isn't there (Iain, 2026-11-08). So pairing asks before it opens, again before it posts, and
 * while its request is up (`docs/architecture/product.md`, invariant 2).
 */
interface Admitters {
    /** Whether someone who can type a code is attached now. */
    suspend fun anyHere(): Boolean

    /** Returns once nobody who can admit is here, asking every [every]. */
    suspend fun awaitNobody(every: Duration) {
        while (anyHere()) delay(every)
    }

    companion object {
        /** A LAN or loopback host: a request waits on the board for whoever comes, and nothing asks. */
        val WHOEVER_COMES: Admitters = object : Admitters {
            override suspend fun anyHere(): Boolean = true

            override suspend fun awaitNobody(every: Duration): Unit = awaitCancellation()
        }

        /** How often a posted request asks whether someone who can admit it is still there. */
        val CHECK_EVERY: Duration = 5.seconds
    }
}

/**
 * A hosted board's admitters: the people ([PeerKind.Human]) in the host's live [roster], since
 * only a person admits ([Pairing]). Every page there holds a key, as loopback grants nothing.
 * The kind is the one a presence entry declares, never attested (bee#64), so an agent that posts
 * itself as a person counts as one here; it still can't admit, so the request would wait for
 * nobody. The roster is ready once the host's relay is.
 */
class PeopleAttached(private val roster: Deferred<LivePresence>) : Admitters {
    override suspend fun anyHere(): Boolean = roster.await().peers().keys.any { it.kind == PeerKind.Human }
}
