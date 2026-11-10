package us.tractat.bee.host

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.bee.core.PairingMessage
import us.tractat.bee.core.PairingPoll
import us.tractat.bee.core.PairingWire
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Pairing by poll (bee#209), the host's one pairing transport (bee#211): the pairing [desk]
 * serves, over plain requests and complete responses, so a proxy that holds a stream back until it
 * ends can't stall it. [open] starts a pairing and answers its code with a secret token; [poll]
 * with that token answers the newest message, until the pairing's device stops asking.
 *
 * **Not asking is hanging up.** A device that no poll has come from for [idle] has left: its
 * pairing is cancelled, which ends it and withdraws a posted request ([PairingDesk.serve]), and
 * its token stops answering.
 *
 * **What the host holds is bounded.** A refused open ([PairingPoll.Refused]) holds nothing: no
 * token, no session. A pairing that ended, with a key or without, keeps its last message for
 * [idle] from its end, so a device whose poll was lost still gets its key on the next one; polls
 * don't stretch that.
 *
 * **A device that never asks writes nothing** (bee#211). Its request goes up only once the dwell
 * has passed and its first poll has come ([PairingDesk.serve]); one that opens and never asks
 * again is let go at [idle] with nothing posted. A first poll that comes late, past the dwell,
 * as one does when a proxy eats the first few (bee#209), puts the request up then.
 *
 * **The token is the capability.** The pairing id is on the board, so it fetches nothing here: only
 * the token [open] gave the device does. Sessions are found by the token's SHA-256, so a lookup's
 * timing says something about a digest, never about the token.
 *
 * @param scope where each pairing runs between polls; the host's own, cancelled when it stops
 */
class PairingPolls(
    private val desk: PairingDesk,
    private val scope: CoroutineScope,
    private val random: SecureRandom,
    private val idle: Duration = PairingWire.IDLE,
) {
    /** One open pairing's state between polls, from its [code] on; [asked] completes on its first poll. */
    private class Session(code: PairingMessage.Code, @Volatile var seen: Instant, val asked: CompletableDeferred<Unit>) {
        @Volatile var latest: PairingMessage = code
            private set

        @Volatile private var endedAt: Instant? = null

        /** The pairing's last message, [message], came at [at]. */
        fun end(message: PairingMessage, at: Instant) {
            latest = message
            endedAt = at
        }

        /** When the host lets this session go: [idle] after its end, or else after its last poll. */
        fun deadline(idle: Duration): Instant = (endedAt ?: seen) + idle
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** The desk's clock, so a pairing's polls and its expiry keep one time. */
    private val clock: () -> Instant = desk.clock

    /** Opens a pairing for [device]: its code and the token to poll by, or why it was refused. */
    suspend fun open(device: Device): PairingPoll {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val digest = digest(token)
        val first = CompletableDeferred<PairingMessage>()
        val asked = CompletableDeferred<Unit>()
        scope.launch {
            try {
                coroutineScope {
                    lateinit var session: Session
                    val serving = launch {
                        desk.serve(device, asked::await) { message ->
                            if (first.isCompleted) {
                                session.end(message, clock())
                            } else {
                                // Only a code opens a session: a refusal leaves nothing behind.
                                if (message is PairingMessage.Code) session = Session(message, clock(), asked).also { sessions[digest] = it }
                                first.complete(message)
                            }
                        }
                    }
                    serving.invokeOnCompletion { first.completeExceptionally(IllegalStateException("The pairing ended before its first message.", it)) }
                    if (first.await() is PairingMessage.Code) {
                        awaitDeadline(session)
                        serving.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(failure) { "A pairing by poll failed; its device is told the host lost it." }
            } finally {
                sessions.remove(digest)
            }
        }
        return when (val message = first.await()) {
            is PairingMessage.Code -> PairingPoll.Opened(token, message)
            is PairingMessage.Ended -> PairingPoll.Refused(message)
            is PairingMessage.Key -> error("A pairing's first message is never a key.")
        }
    }

    /** The newest message for the pairing [token] opened, or null if the host holds none for it. Counts as the device still being here. */
    fun poll(token: String): PairingMessage? = sessions[digest(token)]?.let { session ->
        session.seen = clock()
        session.asked.complete(Unit)
        session.latest
    }

    /** How many pairings by poll the host holds now. */
    internal fun holding(): Int = sessions.size

    /** Returns once [session]'s deadline has passed. */
    private suspend fun awaitDeadline(session: Session) {
        while (true) {
            val left = session.deadline(idle) - clock()
            if (!left.isPositive()) return
            delay(left)
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32

        fun digest(token: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()))
    }
}
